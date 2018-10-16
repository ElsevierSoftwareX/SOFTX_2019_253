package soul.algorithm.oversampling

import breeze.linalg.{DenseMatrix, eigSym}
import soul.data.Data
import soul.io.Logger
import soul.util.Utilities._

import scala.util.Random

/** ADOMS algorithm. Original paper: "The Generation Mechanism of Synthetic Minority Class Examples" by Sheng TANG
  * and Si-ping CHEN.
  *
  * @param data     data to work with
  * @param seed     seed to use. If it is not provided, it will use the system time
  * @param file     file to store the log. If its set to None, log process would not be done
  * @param percent  amount of samples N%
  * @param k        number of neighbors
  * @param distance the type of distance to use, hvdm or euclidean
  * @author David López Pretel
  */
class ADOMS(private[soul] val data: Data, private[soul] val seed: Long = System.currentTimeMillis(), file: Option[String] = None,
            percent: Int = 300, k: Int = 5, distance: Distances.Distance = Distances.EUCLIDEAN) {

  private[soul] val minorityClass: Any = -1
  // Remove NA values and change nominal values to numeric values
  private[soul] val x: Array[Array[Double]] = this.data._processedData
  private[soul] val y: Array[Any] = data._originalClasses
  // Logger object to log the execution of the algorithms
  private[soul] val logger: Logger = new Logger
  // Count the number of instances for each class
  private[soul] val counter: Map[Any, Int] = this.y.groupBy(identity).mapValues((_: Array[Any]).length)
  // In certain algorithms, reduce the minority class is forbidden, so let's detect what class is it if minorityClass is set to -1.
  // Otherwise, minorityClass will be used as the minority one
  private[soul] var untouchableClass: Any = this.counter.minBy((c: (Any, Int)) => c._2)._1
  // Index to shuffle (randomize) the data
  private[soul] val index: List[Int] = new util.Random(this.seed).shuffle(this.y.indices.toList)

  /** Compute the first principal component axis
    *
    * @param A the data
    * @return the first principal component axis
    */
  private def PCA(A: Array[Array[Double]]): Array[Double] = {
    val mean: Array[Double] = A.transpose.map(_.sum / A.length)
    // subtract the mean to the data
    val dataNoMean: DenseMatrix[Double] = DenseMatrix(A: _*) :- DenseMatrix(A.map(_ => mean): _*)
    // get the covariance matrix
    val oneDividedByN: Array[Array[Double]] = Array.fill(dataNoMean.cols, dataNoMean.cols)(dataNoMean.rows)
    val S: DenseMatrix[Double] = (dataNoMean.t * dataNoMean) :/ DenseMatrix(oneDividedByN: _*)
    //compute the eigenvectors and eigenvalues of S
    val eigen = eigSym(S)

    //return the first eigenvector because it represent the first principal component axis
    eigen.eigenvectors(0, ::).t.toArray
  }

  /** Compute the ADOMS algorithm
    *
    * @return synthetic samples generated
    */
  def compute(): Unit = {
    // Start the time
    val initTime: Long = System.nanoTime()

    var samples: Array[Array[Double]] = data._processedData
    if (distance == Distances.EUCLIDEAN) {
      samples = zeroOneNormalization(data)
    }

    // compute minority class
    val minorityClassIndex: Array[Int] = minority(data._originalClasses)
    data._minorityClass = data._originalClasses(minorityClassIndex(0))

    // output with a size of T*N samples
    val output: Array[Array[Double]] = Array.fill(minorityClassIndex.length * percent / 100, samples(0).length)(0.0)

    // index array to save the neighbors of each sample
    var neighbors: Array[Int] = new Array[Int](minorityClassIndex.length)

    var newIndex: Int = 0
    val r: Random = new Random(this.seed)

    (0 until percent / 100).foreach(_ => {
      // for each minority class sample
      minorityClassIndex.zipWithIndex.foreach(i => {
        neighbors = kNeighbors(minorityClassIndex map samples, i._2, k, distance, data._nominal.length == 0,
          (minorityClassIndex map samples, minorityClassIndex map data._originalClasses))
        // calculate first principal component axis of local data distribution
        val l2: Array[Double] = PCA((neighbors map minorityClassIndex) map samples)
        val n: Int = r.nextInt(neighbors.length)
        val D: Double = computeDistanceOversampling(samples(i._1), samples(minorityClassIndex(neighbors(n))), distance,
          data._nominal.length == 0, (minorityClassIndex map samples, minorityClassIndex map data._originalClasses))
        // compute projection of n in l2, M is on l2
        val dotMN: Double = l2.indices.map(j => {
          samples(i._1)(j) - samples(minorityClassIndex(neighbors(n)))(j)
        }).toArray.zipWithIndex.map(j => {
          j._1 * l2(j._2)
        }).sum
        val dotMM: Double = l2.map(x => x * x).sum
        // create synthetic sample
        output(newIndex) = l2.indices.map(j => samples(i._1)(j) + dotMN / dotMM * l2(j) * D * r.nextFloat()).toArray
        newIndex = newIndex + 1
      })
    })

    val dataShuffled: Array[Int] = r.shuffle((0 until samples.length + output.length).indices.toList).toArray
    // check if the data is nominal or numerical
    if (data._nominal.length == 0) {
      data._resultData = dataShuffled map to2Decimals(Array.concat(data._processedData, if (distance == Distances.EUCLIDEAN)
        zeroOneDenormalization(output, data._maxAttribs, data._minAttribs) else output))
    } else {
      data._resultData = dataShuffled map toNominal(Array.concat(data._processedData, if (distance == Distances.EUCLIDEAN)
        zeroOneDenormalization(output, data._maxAttribs, data._minAttribs) else output), data._nomToNum)
    }
    data._resultClasses = dataShuffled map Array.concat(data._originalClasses, Array.fill(output.length)(data._minorityClass))

    // Stop the time
    val finishTime: Long = System.nanoTime()

    if (file.isDefined) {
      this.logger.addMsg("ORIGINAL SIZE: %d".format(data._originalData.length))
      this.logger.addMsg("NEW DATA SIZE: %d".format(data._resultData.length))
      this.logger.addMsg("NEW SAMPLES ARE:")
      dataShuffled.zipWithIndex.foreach((index: (Int, Int)) => if (index._1 >= samples.length) this.logger.addMsg("%d".format(index._2)))
      // Save the time
      this.logger.addMsg("TOTAL ELAPSED TIME: %s".format(nanoTimeToString(finishTime - initTime)))

      // Save the log
      this.logger.storeFile(file.get)
    }
  }
}
