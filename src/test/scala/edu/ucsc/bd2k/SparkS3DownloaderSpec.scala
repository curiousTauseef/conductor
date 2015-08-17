package edu.ucsc.bd2k

import java.io.{File, FileOutputStream}
import java.net.URI
import java.util

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.S3ObjectSummary
import org.apache.spark.{SparkContext, SparkConf}
import org.scalatest._

import scala.collection.mutable.Stack
import scala.util.Random

class SparkS3DownloaderSpec extends FlatSpec
with Matchers with BeforeAndAfter {

  val master = "spark://54.187.162.127:7077"
  val conf = new SparkConf().setMaster(master).setAppName("spark-s3-downloader Tests")
  val sc = new SparkContext(conf)
  val partSize = 64 * 1024 * 1024

  val credentials = Credentials()
  val s3 = new AmazonS3Client(credentials.toAwsCredentials)
  val bucketName = "s3-downloader-tests"
  s3.createBucket(bucketName)

  val simple = "simple_file"
  val simpleBytes: Array[Byte] = uploadFile(partSize, simple)
  val simpleSrc = "s3://" + bucketName + "/" + simple
  val simpleDst = "hdfs://" + simple
  val simpleSs3d =
    new SparkS3Downloader(
      credentials,
      partSize,
      partSize,
      new URI(simpleSrc),
      new URI(simpleDst),
      true)

  val big = "big_file"
  val bigFileSize = 64 * 1024 * 1024 * 5 / 2
  // A file to be divided into 2 full partitions and one half paritition
  val bigBytes: Array[Byte] = uploadFile(bigFileSize, big)
  val bigSrc = "s3://" + bucketName + "/" + big
  val bigDst = "hdfs://" + big
  val bigSs3d =
    new SparkS3Downloader(
      credentials,
      partSize,
      partSize,
      new URI(bigSrc),
      new URI(bigDst),
      true)

  var ss3u: SparkS3Uploader = null
  val uploadName = "upload_test"

  after {
    sc.stop()
    val summaries = s3.listObjects(bucketName).getObjectSummaries
    for (n <- 0 to summaries.size() - 1) {
      s3.deleteObject(bucketName, summaries.get(n).getKey)

    }
    s3.deleteBucket(bucketName)
  }

  def uploadFile(size: Int, name: String): Array[Byte] = {
    val dst = name
    val bytes = new Array[Byte](size)
    val random = new Random()
    random.nextBytes(bytes)
    val fos = new FileOutputStream(new File(dst))
    fos.write(bytes)
    s3.putObject(bucketName, name, new java.io.File(dst))
    bytes
  }

  "A Stack" should "pop values in last-in-first-out order" in {
    val stack = new Stack[Int]
    stack.push(1)
    stack.push(2)
    stack.pop() should be (2)
    stack.pop() should be (1)
  }

  it should "throw NoSuchElementException if an empty stack is popped" in {
    val emptyStack = new Stack[Int]
    a [NoSuchElementException] should be thrownBy {
      emptyStack.pop()
    }
  }

  "The partition method" should "assign partitions correctly-sized to the " +
    "partition size" in {
    val partitionResult = simpleSs3d.partition(partSize).toArray
    assert(partitionResult.length == 1)
    assert(partitionResult(0).getSize == partSize)
    assert(partitionResult(0).getStart == 0)

    val minusResult = simpleSs3d.partition(partSize - 1).toArray
    assert(minusResult.length == 1)
    assert(minusResult(0).getSize == partSize - 1)
    assert(minusResult(0).getStart == 0)

    val plusResult = simpleSs3d.partition(partSize + 1).toArray
    assert(plusResult.length == 2)
    assert(plusResult(0).getSize == partSize)
    assert(plusResult(0).getStart == 0)
    assert(plusResult(1).getSize == 1)
    assert(plusResult(1).getStart == partSize)

    val oneResult = simpleSs3d.partition(1).toArray
    assert(oneResult.length == 1)
    assert(oneResult(0).getSize == 1)
    assert(oneResult(0).getStart == 0)

    val bigResult = bigSs3d.partition(bigFileSize).toArray
    assert(bigResult.length == 3)
    assert(bigResult(0).getSize == partSize)
    assert(bigResult(0).getStart == 0)
    assert(bigResult(1).getSize == partSize)
    assert(bigResult(1).getStart == partSize)
    assert(bigResult(2).getSize == partSize / 2)
    assert(bigResult(2).getStart == partSize * 2)
  }

  "The download method" should "download bytes in the partition that are " +
    "identical to the bytes randomly created in those positions of the " +
    "file" in {
    val simpleResult = simpleSs3d.partition(partSize).toArray
    val simplePart = simpleResult(0)
    assert(simpleSs3d.downloadPart(simplePart).sameElements(simpleBytes))

    val bigResult = bigSs3d.partition(bigFileSize).toArray
    assert(bigSs3d.downloadPart(bigResult(0))
      .sameElements(util.Arrays.copyOfRange(bigBytes, 0, partSize)))
    assert(bigSs3d.downloadPart(bigResult(1))
      .sameElements(util.Arrays.copyOfRange(bigBytes, partSize, 2 * partSize)))
    assert(bigSs3d.downloadPart(bigResult(2))
      .sameElements(util.Arrays.copyOfRange(bigBytes, 2 * partSize, bigFileSize)))
  }
}