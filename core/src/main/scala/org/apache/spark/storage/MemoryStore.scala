/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.storage

import java.nio.ByteBuffer
import java.util.LinkedHashMap

import java.util.{Date, Locale}
import java.text.DateFormat
import java.text.DateFormat._
import java.text.SimpleDateFormat

import java.util.LinkedList
import java.lang.System

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.util.{SizeEstimator, Utils}
import org.apache.spark.util.collection.SizeTrackingVector

import NaiveBayes._

import java.io.PrintWriter

private case class MemoryEntry(value: Any, size: Long, deserialized: Boolean)

/**
 * Stores blocks in memory, either as Arrays of deserialized Java objects or as
 * serialized ByteBuffers.
 */
private[spark] class MemoryStore(blockManager: BlockManager, maxMemory: Long)
  extends BlockStore(blockManager) {

  private val conf = blockManager.conf
  private val entries = new LinkedHashMap[BlockId, MemoryEntry](32, 0.75f, true)
  
  logInfo(s"*******************************************************")
  logInfo(s"*******************************************************")
  logInfo(s"*********                                   ***********")
  logInfo(s"*********        creation of usage          ***********")
  logInfo(s"*********                                   ***********")
  logInfo(s"*******************************************************")
  logInfo(s"*******************************************************")
  //usage is used to contain the time when Block is inserted or used.
  private[spark] val usage = new LinkedHashMap[BlockId, LinkedList[Long]]()
  private[spark] val hitMiss = new LinkedHashMap[BlockId, LinkedList[Boolean]]() //hit is true

  //TODO: get value of useBayes from configuration, false stands for not using bayes.
  val useBayes = true
  var dataset : DataSet = null
  var eva : Evaluation = null

  @volatile private var currentMemory = 0L
  
  //create the bayse classifier.
  // for (String path : dataPaths) {
  if(useBayes) {
    dataset = new DataSet("segment.data")

    eva = new Evaluation(dataset, "NaiveBayes")
    eva.crossValidation(2)
  }
  // val testonly = Array(4000.0)
  // val prediction = test.predict(testonly)
  // print mean and standard deviation of accuracy
  // System.out.println("Dataset:" + path + ", mean and standard deviation of accuracy:" + eva.getAccMean() + "," + eva.getAccStd());
  // Ensure only one thread is putting, and if necessary, dropping blocks at any given time
  private val accountingLock = new Object

  // A mapping from thread ID to amount of memory used for unrolling a block (in bytes)
  // All accesses of this map are assumed to have manually synchronized on `accountingLock`
  private val unrollMemoryMap = mutable.HashMap[Long, Long]()
  // Same as `unrollMemoryMap`, but for pending unroll memory as defined below.
  // Pending unroll memory refers to the intermediate memory occupied by a thread
  // after the unroll but before the actual putting of the block in the cache.
  // This chunk of memory is expected to be released *as soon as* we finish
  // caching the corresponding block as opposed to until after the task finishes.
  // This is only used if a block is successfully unrolled in its entirety in
  // memory (SPARK-4777).
  private val pendingUnrollMemoryMap = mutable.HashMap[Long, Long]()

  /**
   * The amount of space ensured for unrolling values in memory, shared across all cores.
   * This space is not reserved in advance, but allocated dynamically by dropping existing blocks.
   */
  private val maxUnrollMemory: Long = {
    val unrollFraction = conf.getDouble("spark.storage.unrollFraction", 0.2)
    (maxMemory * unrollFraction).toLong
  }

  // Initial memory to request before unrolling any block
  private val unrollMemoryThreshold: Long =
    conf.getLong("spark.storage.unrollMemoryThreshold", 1024 * 1024)

  if (maxMemory < unrollMemoryThreshold) {
    logWarning(s"Max memory ${Utils.bytesToString(maxMemory)} is less than the initial memory " +
      s"threshold ${Utils.bytesToString(unrollMemoryThreshold)} needed to store a block in " +
      s"memory. Please configure Spark with more memory.")
  }

  logInfo("MemoryStore started with capacity %s".format(Utils.bytesToString(maxMemory)))

  private def addHitMiss(blockId:BlockId, hit:Boolean) {
    val ll = hitMiss.get(blockId)
    if(ll == null) {
      val nll = new LinkedList[Boolean]()
      nll.add(hit)
      hitMiss.put(blockId, nll)
    }
    else
      ll.add(hit)
  }

  private def getEntry(blockId:BlockId) = {
    val v = entries.get(blockId)
    if(v == null)
      addHitMiss(blockId, false)
    else
      addHitMiss(blockId, true)
    v
  }

  /** Free memory not occupied by existing blocks. Note that this does not include unroll memory. */
  def freeMemory: Long = maxMemory - currentMemory

  override def getSize(blockId: BlockId): Long = {
    synchronized {
      entries.synchronized {
        getEntry(blockId).size        
      }
      // record access time for blockId in data structure
      logInfo(s"CMU - Usage data structure updated with new time entry. " +
              "Block $blockId acessed at time %s" + String.valueOf(System.currentTimeMillis()))
      if(usage.get(blockId) == null)
        usage.put(blockId, new LinkedList[Long]())
      usage.get(blockId).add(System.currentTimeMillis())
      getEntry(blockId).size
    }
  }

  override def putBytes(blockId: BlockId, _bytes: ByteBuffer, level: StorageLevel): PutResult = {
    // Work on a duplicate - since the original input might be used elsewhere.
    val bytes = _bytes.duplicate()
    bytes.rewind()
    if (level.deserialized) {
      val values = blockManager.dataDeserialize(blockId, bytes)
      putIterator(blockId, values, level, returnValues = true)
    } else {
      val putAttempt = tryToPut(blockId, bytes, bytes.limit, deserialized = false)
      PutResult(bytes.limit(), Right(bytes.duplicate()), putAttempt.droppedBlocks)
    }
  }

  override def putArray(
      blockId: BlockId,
      values: Array[Any],
      level: StorageLevel,
      returnValues: Boolean): PutResult = {
    if (level.deserialized) {
      val sizeEstimate = SizeEstimator.estimate(values.asInstanceOf[AnyRef])
      val putAttempt = tryToPut(blockId, values, sizeEstimate, deserialized = true)
      PutResult(sizeEstimate, Left(values.iterator), putAttempt.droppedBlocks)
    } else {
      val bytes = blockManager.dataSerialize(blockId, values.iterator)
      val putAttempt = tryToPut(blockId, bytes, bytes.limit, deserialized = false)
      PutResult(bytes.limit(), Right(bytes.duplicate()), putAttempt.droppedBlocks)
    }
  }

  override def putIterator(
      blockId: BlockId,
      values: Iterator[Any],
      level: StorageLevel,
      returnValues: Boolean): PutResult = {
    putIterator(blockId, values, level, returnValues, allowPersistToDisk = true)
  }

  /**
   * Attempt to put the given block in memory store.
   *
   * There may not be enough space to fully unroll the iterator in memory, in which case we
   * optionally drop the values to disk if
   *   (1) the block's storage level specifies useDisk, and
   *   (2) `allowPersistToDisk` is true.
   *
   * One scenario in which `allowPersistToDisk` is false is when the BlockManager reads a block
   * back from disk and attempts to cache it in memory. In this case, we should not persist the
   * block back on disk again, as it is already in disk store.
   */
  private[storage] def putIterator(
      blockId: BlockId,
      values: Iterator[Any],
      level: StorageLevel,
      returnValues: Boolean,
      allowPersistToDisk: Boolean): PutResult = {
    val droppedBlocks = new ArrayBuffer[(BlockId, BlockStatus)]
    val unrolledValues = unrollSafely(blockId, values, droppedBlocks)
    unrolledValues match {
      case Left(arrayValues) =>
        // Values are fully unrolled in memory, so store them as an array
        val res = putArray(blockId, arrayValues, level, returnValues)
        droppedBlocks ++= res.droppedBlocks
        PutResult(res.size, res.data, droppedBlocks)
      case Right(iteratorValues) =>
        // Not enough space to unroll this block; drop to disk if applicable
        if (level.useDisk && allowPersistToDisk) {
          logWarning(s"Persisting block $blockId to disk instead.")
          val res = blockManager.diskStore.putIterator(blockId, iteratorValues, level, returnValues)
          PutResult(res.size, res.data, droppedBlocks)
        } else {
          PutResult(0, Left(iteratorValues), droppedBlocks)
        }
    }
  }

  override def getBytes(blockId: BlockId): Option[ByteBuffer] = {
    synchronized {
      val entry = entries.synchronized {
        getEntry(blockId)
      }
      // record access time for blockId in data structure
      logInfo(s"CMU - Usage data structure updated with new time entry. " +
              "Block $blockId acessed at time %s" + String.valueOf(System.currentTimeMillis()))
      if(usage.get(blockId) == null)
        usage.put(blockId, new LinkedList[Long]())
      usage.get(blockId).add(System.currentTimeMillis())
      if (entry == null) {
        None
      } else if (entry.deserialized) {
        //TODO: print out the ByteBuffer.
        Some(blockManager.dataSerialize(blockId, entry.value.asInstanceOf[Array[Any]].iterator))
      } else {
        Some(entry.value.asInstanceOf[ByteBuffer].duplicate()) // Doesn't actually copy the data
      }
    }
  }

  override def getValues(blockId: BlockId): Option[Iterator[Any]] = {
    synchronized {
      val entry = entries.synchronized {
        getEntry(blockId)
      }
      // record access time for blockId in data structure
      logInfo(s"CMU - Usage data structure updated with new time entry. " +
              "Block $blockId acessed at time %s" + String.valueOf(System.currentTimeMillis()))
      if(usage.get(blockId) == null)
        usage.put(blockId, new LinkedList[Long]())
      usage.get(blockId).add(System.currentTimeMillis())
      if (entry == null) {
        None
      } else if (entry.deserialized) {
        //TODO: try to get more information about Iterator.
        Some(entry.value.asInstanceOf[Array[Any]].iterator)
      } else {
        val buffer = entry.value.asInstanceOf[ByteBuffer].duplicate() // Doesn't actually copy data
        Some(blockManager.dataDeserialize(blockId, buffer))
      }
    }
  }

  override def remove(blockId: BlockId): Boolean = {
    synchronized {
      entries.synchronized {
        val entry = entries.remove(blockId)
        if (entry != null) {
          currentMemory -= entry.size
          logDebug(s"Block $blockId of size ${entry.size} dropped from memory (free $freeMemory)")
        }
      }
      // remove blockId in data structure
      logInfo(s"CMU - Usage data structure update. " +
              "Block $blockId removed at time %s" + String.valueOf(System.currentTimeMillis()))
      if(usage.get(blockId) != null) {
        usage.remove(blockId)
        true
      } else {
        false
      }
    }
  }

  override def clear() {
    synchronized {
      entries.synchronized {
        entries.clear()
        currentMemory = 0
      }
      // remove data structure
      logInfo(s"CMU - Usage data structure cleared. ")
      usage.clear()
      logInfo("MemoryStore cleared")
    }
  }

  /**
   * Unroll the given block in memory safely.
   *
   * The safety of this operation refers to avoiding potential OOM exceptions caused by
   * unrolling the entirety of the block in memory at once. This is achieved by periodically
   * checking whether the memory restrictions for unrolling blocks are still satisfied,
   * stopping immediately if not. This check is a safeguard against the scenario in which
   * there is not enough free memory to accommodate the entirety of a single block.
   *
   * This method returns either an array with the contents of the entire block or an iterator
   * containing the values of the block (if the array would have exceeded available memory).
   */
  def unrollSafely(
      blockId: BlockId,
      values: Iterator[Any],
      droppedBlocks: ArrayBuffer[(BlockId, BlockStatus)])
    : Either[Array[Any], Iterator[Any]] = {

    // Number of elements unrolled so far
    var elementsUnrolled = 0
    // Whether there is still enough memory for us to continue unrolling this block
    var keepUnrolling = true
    // Initial per-thread memory to request for unrolling blocks (bytes). Exposed for testing.
    val initialMemoryThreshold = unrollMemoryThreshold
    // How often to check whether we need to request more memory
    val memoryCheckPeriod = 16
    // Memory currently reserved by this thread for this particular unrolling operation
    var memoryThreshold = initialMemoryThreshold
    // Memory to request as a multiple of current vector size
    val memoryGrowthFactor = 1.5
    // Previous unroll memory held by this thread, for releasing later (only at the very end)
    val previousMemoryReserved = currentUnrollMemoryForThisThread
    // Underlying vector for unrolling the block
    var vector = new SizeTrackingVector[Any]

    // Request enough memory to begin unrolling
    keepUnrolling = reserveUnrollMemoryForThisThread(initialMemoryThreshold)

    if (!keepUnrolling) {
      logWarning(s"Failed to reserve initial memory threshold of " +
        s"${Utils.bytesToString(initialMemoryThreshold)} for computing block $blockId in memory.")
    }

    // Unroll this block safely, checking whether we have exceeded our threshold periodically
    try {
      while (values.hasNext && keepUnrolling) {
        vector += values.next()
        if (elementsUnrolled % memoryCheckPeriod == 0) {
          // If our vector's size has exceeded the threshold, request more memory
          val currentSize = vector.estimateSize()
          if (currentSize >= memoryThreshold) {
            val amountToRequest = (currentSize * memoryGrowthFactor - memoryThreshold).toLong
            // Hold the accounting lock, in case another thread concurrently puts a block that
            // takes up the unrolling space we just ensured here
            accountingLock.synchronized {
              if (!reserveUnrollMemoryForThisThread(amountToRequest)) {
                // If the first request is not granted, try again after ensuring free space
                // If there is still not enough space, give up and drop the partition
                val spaceToEnsure = maxUnrollMemory - currentUnrollMemory
                if (spaceToEnsure > 0) {
                  val result = ensureFreeSpace(blockId, spaceToEnsure)
                  droppedBlocks ++= result.droppedBlocks
                }
                keepUnrolling = reserveUnrollMemoryForThisThread(amountToRequest)
              }
            }
            // New threshold is currentSize * memoryGrowthFactor
            memoryThreshold += amountToRequest
          }
        }
        elementsUnrolled += 1
      }

      if (keepUnrolling) {
        // We successfully unrolled the entirety of this block
        Left(vector.toArray)
      } else {
        // We ran out of space while unrolling the values for this block
        logUnrollFailureMessage(blockId, vector.estimateSize())
        Right(vector.iterator ++ values)
      }

    } finally {
      // If we return an array, the values returned will later be cached in `tryToPut`.
      // In this case, we should release the memory after we cache the block there.
      // Otherwise, if we return an iterator, we release the memory reserved here
      // later when the task finishes.
      if (keepUnrolling) {
        accountingLock.synchronized {
          val amountToRelease = currentUnrollMemoryForThisThread - previousMemoryReserved
          releaseUnrollMemoryForThisThread(amountToRelease)
          reservePendingUnrollMemoryForThisThread(amountToRelease)
        }
      }
    }
  }

  /**
   * Return the RDD ID that a given block ID is from, or None if it is not an RDD block.
   */
  private def getRddId(blockId: BlockId): Option[Int] = {
    blockId.asRDDId.map(_.rddId)
  }

  /**
   * Try to put in a set of values, if we can free up enough space. The value should either be
   * an Array if deserialized is true or a ByteBuffer otherwise. Its (possibly estimated) size
   * must also be passed by the caller.
   *
   * Synchronize on `accountingLock` to ensure that all the put requests and its associated block
   * dropping is done by only on thread at a time. Otherwise while one thread is dropping
   * blocks to free memory for one block, another thread may use up the freed space for
   * another block.
   *
   * Return whether put was successful, along with the blocks dropped in the process.
   */
  private def tryToPut(
      blockId: BlockId,
      value: Any,
/*TODO: deep research. print size. */
      size: Long,
      deserialized: Boolean): ResultWithDroppedBlocks = {

    /* TODO: Its possible to optimize the locking by locking entries only when selecting blocks
     * to be dropped. Once the to-be-dropped blocks have been selected, and lock on entries has
     * been released, it must be ensured that those to-be-dropped blocks are not double counted
     * for freeing up more space for another block that needs to be put. Only then the actually
     * dropping of blocks (and writing to disk if necessary) can proceed in parallel. */

    var putSuccess = false
    val droppedBlocks = new ArrayBuffer[(BlockId, BlockStatus)]

    accountingLock.synchronized {
      val freeSpaceResult = ensureFreeSpace(blockId, size)
      val enoughFreeSpace = freeSpaceResult.success
      droppedBlocks ++= freeSpaceResult.droppedBlocks

      if (enoughFreeSpace) {
        val entry = new MemoryEntry(value, size, deserialized)
        synchronized {
          entries.synchronized {
            entries.put(blockId, entry)
            currentMemory += size
          }
          // record access time for blockId in data structure
          logInfo(s"CMU - Usage data structure updated with new time entry. " +
                  "Block $blockId acessed at time %s" + String.valueOf(System.currentTimeMillis()))
          if(usage.get(blockId) == null)
            usage.put(blockId, new LinkedList[Long]())
          usage.get(blockId).add(System.currentTimeMillis())
        }
        val valuesOrBytes = if (deserialized) "values" else "bytes"
        logInfo("Block %s stored as %s in memory (estimated size %s, free %s)".format(
          blockId, valuesOrBytes, Utils.bytesToString(size), Utils.bytesToString(freeMemory)))
        putSuccess = true
      } else {
        // Tell the block manager that we couldn't put it in memory so that it can drop it to
        // disk if the block allows disk storage.
        val data = if (deserialized) {
          Left(value.asInstanceOf[Array[Any]])
        } else {
          Right(value.asInstanceOf[ByteBuffer].duplicate())
        }
        val droppedBlockStatus = blockManager.dropFromMemory(blockId, data)
        droppedBlockStatus.foreach { status => droppedBlocks += ((blockId, status)) }
      }
      // Release the unroll memory used because we no longer need the underlying Array
      releasePendingUnrollMemoryForThisThread()
    }
    ResultWithDroppedBlocks(putSuccess, droppedBlocks)
  }


  /**
  *Define the ways to choose which blcok to swap out
  */
  //question: the parameters passed in are val?
  private def findBlocksToReplace (
    entries: LinkedHashMap[BlockId, MemoryEntry],
    actualFreeMemory: Long,
    space: Long,
    rddToAdd: Option[Int],
    selectedBlocks: ArrayBuffer[BlockId],
    selectedMemory: Long) : Long = {
    var resultSelectedMemory = selectedMemory
    synchronized {
      entries.synchronized {
        val cmuEntries = entries.entrySet()
        val iterator = cmuEntries.iterator()
        while (actualFreeMemory + selectedMemory < space && iterator.hasNext) {
          val pair = iterator.next()
          val blockId = pair.getKey
          if (rddToAdd.isEmpty || rddToAdd != getRddId(blockId)) {
            logInfo(s"########################## blockId is $blockId ##############")
            selectedBlocks += blockId
            resultSelectedMemory += pair.getValue.size
            logInfo(s"Block: " + String.valueOf(blockId)
                + s" timeLine: " + String.valueOf(usage.get(blockId).get(0))
                + s" access frequency: " + String.valueOf(usage.get(blockId).size()));
          }
        }
      }
      logInfo(s"----------------------------test for bayse------------------------")
      var usageEntries = usage.entrySet()
      var usageIterator = usageEntries.iterator()
      if(usageIterator.hasNext) {
        var usagePair = usageIterator.next()
        var usageBlockId = usagePair.getKey
        var predict = eva.predict(Array(usage.get(usageBlockId).size(), entries.get(usageBlockId).size))
        logInfo(s"BlockId:" + String.valueOf(usageBlockId) 
          + s" frequency:" + String.valueOf(usage.get(usageBlockId).size())
          + s" block size:" + String.valueOf(entries.get(usageBlockId).size)
          + s" last access rate:" + String.valueOf((usage.get(usageBlockId).getLast() * 1.0) / (System.currentTimeMillis() * 1.0)) 
          + s" predict:" + String.valueOf(predict))
        while(usageIterator.hasNext) {
          usagePair = usageIterator.next()
          var usageTempBlockId = usagePair.getKey
          var tempPredict = eva.predict(Array(usage.get(usageTempBlockId).size(), entries.get(usageBlockId).size))
          logInfo(s"BlockId:" + String.valueOf(usageBlockId) 
          + s" frequency:" + String.valueOf(usage.get(usageBlockId).size())
          + s" block size:" + String.valueOf(entries.get(usageBlockId).size)
          + s" last access rate:" + String.valueOf((usage.get(usageBlockId).getLast() * 1.0) / (System.currentTimeMillis() * 1.0)) 
          + s" predict:" + String.valueOf(predict))
          if(predict > tempPredict) {
            predict = tempPredict
            usageBlockId = usageTempBlockId
          }
        }
        logInfo(s"Choose to drop Block: " + String.valueOf(usageBlockId)
          + s" timeLine: " + String.valueOf(usage.get(usageBlockId).getLast())
          + s" access frequency: " + String.valueOf(usage.get(usageBlockId).size()));
      }
      logInfo(s"----------------------------test end------------------------")
    // TODO: utilize usage structure
    
    }
    resultSelectedMemory
  }
  
  
  private def findBlocksToReplaceOriginal (
    entries: LinkedHashMap[BlockId, MemoryEntry],
    actualFreeMemory: Long,
    space: Long,
    rddToAdd: Option[Int],
    selectedBlocks: ArrayBuffer[BlockId],
    selectedMemory: Long) : Long = {
  // This is synchronized to ensure that the set of entries is not changed
  // (because of getValue or getBytes) while traversing the iterator, as that
  // can lead to exceptions.
    var resultSelectedMemory = selectedMemory
    entries.synchronized {
      val iterator = entries.entrySet().iterator()
      while (actualFreeMemory + selectedMemory < space && iterator.hasNext) {
        val pair = iterator.next()
        val blockId = pair.getKey
        if (rddToAdd.isEmpty || rddToAdd != getRddId(blockId)) {
          selectedBlocks += blockId
          resultSelectedMemory += pair.getValue.size
        }
      }
    }
    resultSelectedMemory
  }

  /**
   * Try to free up a given amount of space to store a particular block, but can fail if
   * either the block is bigger than our memory or it would require replacing another block
   * from the same RDD (which leads to a wasteful cyclic replacement pattern for RDDs that
   * don't fit into memory that we want to avoid).
   *
   * Assume that `accountingLock` is held by the caller to ensure only one thread is dropping
   * blocks. Otherwise, the freed space may fill up before the caller puts in their new value.
   *
   * Return whether there is enough free space, along with the blocks dropped in the process.
   */
  private def ensureFreeSpace(
      blockIdToAdd: BlockId,
      space: Long): ResultWithDroppedBlocks = {
    logInfo(s"ensureFreeSpace($space) called with curMem=$currentMemory, maxMem=$maxMemory")

    val droppedBlocks = new ArrayBuffer[(BlockId, BlockStatus)]

    if (space > maxMemory) {
      logInfo(s"Will not store $blockIdToAdd as it is larger than our memory limit")
      return ResultWithDroppedBlocks(success = false, droppedBlocks)
    }

    // Take into account the amount of memory currently occupied by unrolling blocks
    // and minus the pending unroll memory for that block on current thread.
    val threadId = Thread.currentThread().getId
    val actualFreeMemory = freeMemory - currentUnrollMemory +
      pendingUnrollMemoryMap.getOrElse(threadId, 0L)

    //if (actualFreeMemory < space) {
      val rddToAdd = getRddId(blockIdToAdd)
      val selectedBlocks = new ArrayBuffer[BlockId]
      var selectedMemory = 0L
      
      if(useBayes) {
        selectedMemory = findBlocksToReplace(entries, actualFreeMemory, space, rddToAdd, selectedBlocks, selectedMemory)
      } else {
        selectedMemory = findBlocksToReplaceOriginal(entries, actualFreeMemory, space, rddToAdd, selectedBlocks, selectedMemory)
      }
      if (actualFreeMemory + selectedMemory >= space) {
        logInfo(s"${selectedBlocks.size} blocks selected for dropping")
        for (blockId <- selectedBlocks) {
          logInfo(s"dropping block: " + String.valueOf(blockId))
          synchronized {
            val entry = entries.synchronized { getEntry(blockId) }
            // record access time for blockId in data structure
            logInfo(s"CMU - Usage data structure updated with new time entry. " +
                    "Block $blockId acessed at time %s" + String.valueOf(System.currentTimeMillis()))
            if(usage.get(blockId) == null)
              usage.put(blockId, new LinkedList[Long]())
            usage.get(blockId).add(System.currentTimeMillis())
  
            // This should never be null as only one thread should be dropping
            // blocks and removing entries. However the check is still here for
            // future safety.
            if (entry != null) {
              val data = if (entry.deserialized) {
                Left(entry.value.asInstanceOf[Array[Any]])
              } else {
                Right(entry.value.asInstanceOf[ByteBuffer].duplicate())
              }
              val droppedBlockStatus = blockManager.dropFromMemory(blockId, data)
              droppedBlockStatus.foreach { status => droppedBlocks += ((blockId, status)) }
            }
          }
        }
        return ResultWithDroppedBlocks(success = true, droppedBlocks)
      } else {
        logInfo(s"Will not store $blockIdToAdd as it would require dropping another block " +
          "from the same RDD")
        return ResultWithDroppedBlocks(success = false, droppedBlocks)
      }
    //}
    ResultWithDroppedBlocks(success = true, droppedBlocks)
  }

  override def contains(blockId: BlockId): Boolean = {
    entries.synchronized { entries.containsKey(blockId) }
  }

  /**
   * Reserve additional memory for unrolling blocks used by this thread.
   * Return whether the request is granted.
   */
  def reserveUnrollMemoryForThisThread(memory: Long): Boolean = {
    accountingLock.synchronized {
      val granted = freeMemory > currentUnrollMemory + memory
      if (granted) {
        val threadId = Thread.currentThread().getId
        unrollMemoryMap(threadId) = unrollMemoryMap.getOrElse(threadId, 0L) + memory
      }
      granted
    }
  }

  /**
   * Release memory used by this thread for unrolling blocks.
   * If the amount is not specified, remove the current thread's allocation altogether.
   */
  def releaseUnrollMemoryForThisThread(memory: Long = -1L): Unit = {
    val threadId = Thread.currentThread().getId
    accountingLock.synchronized {
      if (memory < 0) {
        unrollMemoryMap.remove(threadId)
      } else {
        unrollMemoryMap(threadId) = unrollMemoryMap.getOrElse(threadId, memory) - memory
        // If this thread claims no more unroll memory, release it completely
        if (unrollMemoryMap(threadId) <= 0) {
          unrollMemoryMap.remove(threadId)
        }
      }
    }
  }

  /**
   * Reserve the unroll memory of current unroll successful block used by this thread
   * until actually put the block into memory entry.
   */
  def reservePendingUnrollMemoryForThisThread(memory: Long): Unit = {
    val threadId = Thread.currentThread().getId
    accountingLock.synchronized {
       pendingUnrollMemoryMap(threadId) = pendingUnrollMemoryMap.getOrElse(threadId, 0L) + memory
    }
  }

  /**
   * Release pending unroll memory of current unroll successful block used by this thread
   */
  def releasePendingUnrollMemoryForThisThread(): Unit = {
    val threadId = Thread.currentThread().getId
    accountingLock.synchronized {
      pendingUnrollMemoryMap.remove(threadId)
    }
  }

  /**
   * Return the amount of memory currently occupied for unrolling blocks across all threads.
   */
  def currentUnrollMemory: Long = accountingLock.synchronized {
    unrollMemoryMap.values.sum + pendingUnrollMemoryMap.values.sum
  }

  /**
   * Return the amount of memory currently occupied for unrolling blocks by this thread.
   */
  def currentUnrollMemoryForThisThread: Long = accountingLock.synchronized {
    unrollMemoryMap.getOrElse(Thread.currentThread().getId, 0L)
  }

  /**
   * Return the number of threads currently unrolling blocks.
   */
  def numThreadsUnrolling: Int = accountingLock.synchronized { unrollMemoryMap.keys.size }

  /**
   * Log information about current memory usage.
   */
  def logMemoryUsage(): Unit = {
    val blocksMemory = currentMemory
    val unrollMemory = currentUnrollMemory
    val totalMemory = blocksMemory + unrollMemory
    logInfo(
      s"Memory use = ${Utils.bytesToString(blocksMemory)} (blocks) + " +
      s"${Utils.bytesToString(unrollMemory)} (scratch space shared across " +
      s"$numThreadsUnrolling thread(s)) = ${Utils.bytesToString(totalMemory)}. " +
      s"Storage limit = ${Utils.bytesToString(maxMemory)}."
    )
  }

  /**
   * Log a warning for failing to unroll a block.
   *
   * @param blockId ID of the block we are trying to unroll.
   * @param finalVectorSize Final size of the vector before unrolling failed.
   */
  def logUnrollFailureMessage(blockId: BlockId, finalVectorSize: Long): Unit = {
    logWarning(
      s"Not enough space to cache $blockId in memory! " +
      s"(computed ${Utils.bytesToString(finalVectorSize)} so far)"
    )
    logMemoryUsage()
  }
}

private[spark] case class ResultWithDroppedBlocks(
    success: Boolean,
    droppedBlocks: Seq[(BlockId, BlockStatus)])
