#!/usr/bin/env bash
./bin/spark-submit --class edu.cmu.sv.generators.IterativeWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 0
./bin/spark-submit --class edu.cmu.sv.generators.InteractiveWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 0
./bin/spark-submit --class edu.cmu.sv.generators.RandomWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 0

./bin/spark-submit --class edu.cmu.sv.generators.IterativeWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 1
./bin/spark-submit --class edu.cmu.sv.generators.InteractiveWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 1
./bin/spark-submit --class edu.cmu.sv.generators.RandomWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 1

./bin/spark-submit --class edu.cmu.sv.generators.IterativeWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 2
./bin/spark-submit --class edu.cmu.sv.generators.InteractiveWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 2
./bin/spark-submit --class edu.cmu.sv.generators.RandomWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 2

./bin/spark-submit --class edu.cmu.sv.generators.IterativeWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 3
./bin/spark-submit --class edu.cmu.sv.generators.InteractiveWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 3
./bin/spark-submit --class edu.cmu.sv.generators.RandomWorkloadTest --executor-memory 512g --driver-memory 1g --master local-cluster[2,1,512] ./examples/target/scala-2.10/spark-examples-1.3.0-SNAPSHOT-hadoop2.3.0.jar 10 trace 2 3

