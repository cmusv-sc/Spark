package org.apache.spark.storage

import java.util.LinkedHashMap
import java.util.LinkedList
import java.io._
import java.lang.System
import scala.math.random
import org.apache.spark._
import scala.util.Random
import scala.util.control.Breaks._

import java.io.PrintWriter

/* 
 * generates an interactive workload
 * execute with: 
 *    ./bin/spark-submit --class edu.cmu.sv.generators.InteractiveWorkloadTest --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop1.0.4.jar
 *    you can also use --master local[512]
 */
class csvGenerator(usage: LinkedHashMap[BlockId, LinkedList[Long]], hitMiss: LinkedHashMap[BlockId, LinkedList[Boolean]],
  entries: LinkedHashMap[BlockId, MemoryEntry], lastEntryAccessTime: LinkedList[Long]) extends Thread{

  //val usage = _usage
  //val hitMiss = _hitMiss
  
  override def run {
    println(s"CMU - Usage information written to csv file ")
    val out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("usageHistory.csv", true)))

    println(s"CMU - Usage information written to csv file, time: " + String.valueOf(System.currentTimeMillis()))
    var count = 0
    var preLastTime = 0L;
    
    breakable{
      while(true) {
        val currTime = System.currentTimeMillis()
        var lastTime = lastEntryAccessTime.get(0)
        println(s"######################################## Name: " + getName() + " ########################################")
        println(s"######################################## time: " + currTime + " ########################################")
        println(s"######################################## usage size: " + String.valueOf(usage.size) + " ########################################")
        println(s"######################################## hitMiss size: " + String.valueOf(hitMiss.size) + " ########################################")
        val iteratorU = usage.entrySet().iterator()
        while (iteratorU.hasNext) {
          var str = ""
          val pair = iteratorU.next()
          val blockId = pair.getKey
          val time1 = pair.getValue.get(0)
          val count = pair.getValue.size
          val freq = 1000.0 * count / (currTime - time1)
          val blockSize = entries.get(blockId).size
          val ratio = 1.0 * pair.getValue.get(count-1) / currTime
          val label = new Random().nextInt(100)
          str = str + blockId + "," + freq + "," + blockSize + "," + ratio + "," + label + "\n"
          out.write(str)
        }
        if(preLastTime == lastTime)
          count = count + 1
        else 
          count = 0;
        if(count >= 5)
          break
        preLastTime = lastTime
        Thread.sleep(1000)
      }
    }
    out.close()
  }
}
