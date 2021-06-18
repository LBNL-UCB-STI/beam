package beam.utils

import java.io._
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, Files, Path, Paths}
import java.text.SimpleDateFormat
import java.util.stream
import java.util.zip.{GZIPInputStream, ZipEntry, ZipInputStream}

import beam.sim.config.BeamConfig
import beam.utils.UnzipUtility.unzip
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.GetObjectRequest
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io
import org.apache.commons.io.FileUtils.{copyURLToFile, deleteDirectory, getTempDirectoryPath}
import org.apache.commons.io.FilenameUtils.{getBaseName, getExtension, getName}
import org.matsim.core.config.Config
import org.matsim.core.utils.io.{IOUtils, UnicodeInputStream}

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.io.Source
import scala.language.{higherKinds, postfixOps, reflectiveCalls}
import scala.util.{Failure, Random, Success, Try}

/**
  * Created by sfeygin on 1/30/17.
  */
object FileUtils extends LazyLogging {

  val runStartTime: String = getDateString
  val suffixLength = 3

  def randomString(size: Int): String = Random.alphanumeric.filter(_.isLower).take(size).mkString

  def setConfigOutputFile(beamConfig: BeamConfig, matsimConfig: Config): String = {
    val baseOutputDir = Paths.get(beamConfig.beam.outputs.baseOutputDirectory)
    if (!Files.exists(baseOutputDir)) baseOutputDir.toFile.mkdir()

    val optionalSuffix: String = getOptionalOutputPathSuffix(
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )

    val uniqueSuffix = "_" + randomString(suffixLength)
    val outputDir = Paths
      .get(
        beamConfig.beam.outputs.baseOutputDirectory + File.separator + beamConfig.beam.agentsim.simulationName + optionalSuffix + uniqueSuffix
      )
      .toFile
    outputDir.mkdir()
    logger.debug(s"Beam output directory is: ${outputDir.getAbsolutePath}")
    matsimConfig.controler.setOutputDirectory(outputDir.getAbsolutePath)
    outputDir.getAbsolutePath
  }

  def getConfigOutputFile(
    outputDirectoryBasePath: String,
    simulationName: String,
    addTimestampToOutputDirectory: Boolean
  ): String = {
    val baseOutputDir = Paths.get(outputDirectoryBasePath)
    if (!Files.exists(baseOutputDir)) baseOutputDir.toFile.mkdir()

    val optionalSuffix: String = getOptionalOutputPathSuffix(addTimestampToOutputDirectory)
    val uniqueSuffix = randomString(suffixLength)

    val outputDir = Paths
      .get(outputDirectoryBasePath + File.separator + simulationName + "_" + optionalSuffix + "_" + uniqueSuffix)
      .toFile
    outputDir.mkdir()
    outputDir.getAbsolutePath
  }

  def getOptionalOutputPathSuffix(addTimestampToOutputDirectory: Boolean): String = {
    if (addTimestampToOutputDirectory) s"_$runStartTime"
    else ""
  }

  private def getDateString: String =
    new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date())

  def createDirectoryIfNotExists(path: String): Boolean = {
    val dir = new File(path).getAbsoluteFile
    if (!dir.exists() && !dir.isDirectory) {
      dir.mkdirs()
    } else {
      false
    }
  }

  def using[A <: { def close(): Unit }, B](resource: A)(f: A => B): B =
    try {
      f(resource)
    } finally {
      resource.close()
    }

  def using[A, B](resource: A)(close: A => Unit)(f: A => B): B =
    try {
      f(resource)
    } finally {
      close(resource)
    }

  def usingTemporaryDirectory[B](f: Path => B): B = {
    val tmpFolder: Path = Files.createTempDirectory("tempDirectory")
    try {
      f(tmpFolder)
    } finally {
      deleteDirectory(tmpFolder.toFile)
    }
  }

  /**
    * Read file with a given path or creates one if file is missing. It also creates a lock file at the same dir
    * that indicates that file is being created.
    * @param path the file path
    * @param atMost wait at most this time before starting reading the file
    * @param reader the file reader
    * @param writer the file writer
    * @tparam T type of the entity that is read from the file
    * @return the read entity
    */
  def readOrCreateFile[T](path: Path, atMost: Duration = 10.minutes)(
    reader: Path => T
  )(writer: Path => T): Try[T] = {
    val locFile = path.getParent.resolve(path.getFileName.toString + ".lock")

    def readFile: Try[T] = {
      busyWaiting(atMost.toMillis, 1000) { () =>
        !Files.exists(locFile)
      }
      Try { reader(path) }
    }

    if (Files.exists(path))
      readFile
    else {
      val locking = Try { Files.createFile(locFile) }
      locking match {
        case Failure(exception) =>
          exception match {
            case _: FileAlreadyExistsException => readFile
            case throwable                     => Failure(throwable)
          }
        case Success(_) =>
          val tryWrite = Try(writer(path))
          Try(Files.delete(locFile)).failed.foreach { throwable =>
            logger.error(s"Cannot delete lock file $locFile", throwable)
          }
          tryWrite
      }
    }
  }

  @tailrec
  private def busyWaiting(atMostMillis: Long, checkInterval: Int)(f: () => Boolean): Boolean = {
    if (!f()) {
      Thread.sleep(checkInterval)
      val newAtMost = atMostMillis - checkInterval
      if (newAtMost > 0) {
        busyWaiting(newAtMost, checkInterval)(f)
      } else {
        false
      }
    } else {
      true
    }
  }

  def safeLines(fileLoc: String): stream.Stream[String] = {
    using(readerFromFile(fileLoc))(_.lines)
  }

  def getReader(pathOrUrl: String): java.io.BufferedReader = {
    if (isRemote(pathOrUrl, "http://") || isRemote(pathOrUrl, "https://")) {
      readerFromURL(pathOrUrl)
    } else {
      readerFromFile(pathOrUrl)
    }
  }

  def readerFromFile(filePath: String): java.io.BufferedReader = {
    IOUtils.getBufferedReader(filePath)
  }

  def readerFromStream(stream: InputStream): java.io.BufferedReader = {
    new BufferedReader(new InputStreamReader(new UnicodeInputStream(stream), StandardCharsets.UTF_8))
  }

  def readerFromIterator(iterator: Iterator[String]): java.io.Reader = {
    new Reader() {
      var currentLine: String = ""
      var position: Int = 0

      override def read(cbuf: Array[Char], off: Int, len: Int): Int = {
        if (len == 0) return 0
        if (position >= currentLine.length && !receiveNextLine()) return -1
        val read = Math.min(currentLine.length - position, len)
        currentLine.getChars(position, position + read, cbuf, off)
        position += read
        read
      }

      private def receiveNextLine() = {
        if (iterator.hasNext) {
          currentLine = iterator.next()
          position = 0
          true
        } else {
          currentLine = ""
          false
        }
      }

      override def close(): Unit = {}
    }
  }

  def readerFromURL(url: String): java.io.BufferedReader = {
    require(isRemote(url, "http://") || isRemote(url, "https://"))
    new BufferedReader(new InputStreamReader(new UnicodeInputStream(getInputStream(url)), StandardCharsets.UTF_8))
  }

  def getInputStream(pathOrUrl: String): InputStream = {
    val rawStream = if (isRemote(pathOrUrl, "http://") || isRemote(pathOrUrl, "https://")) {
      new URL(pathOrUrl).openStream()
    } else {
      new FileInputStream(pathOrUrl)
    }
    if (pathOrUrl.endsWith(".gz")) {
      new GZIPInputStream(rawStream)
    } else {
      rawStream
    }
  }

  def downloadFile(source: String): Unit = {
    downloadFile(source, Paths.get(getTempDirectoryPath, getName(source)).toString)
  }

  def downloadFile(source: String, target: String): Unit = {
    assert(source != null)
    assert(target != null)
    logger.info(s"Downloading [$source] to [$target]")
    copyURLToFile(new URL(source), Paths.get(target).toFile)
  }

  def downloadS3File(source: String, target: String): Unit = {
    assert(source != null)
    assert(target != null)
    val s3Client = AmazonS3ClientBuilder.standard()
    val localFile = new File(target)

    val keys = source.substring("s3://".length).split("/", 2)
    logger.info(s"Downloading [$source] to [$target]")
    s3Client.build().getObject(new GetObjectRequest(keys(0), keys(1)), localFile)
  }

  def getHash(concatParams: Any*): Int = {
    val concatString = concatParams.foldLeft("")(_ + _)
    concatString.hashCode
  }

  /**
    * Writes data to the output file at specified path.
    * @param filePath path of the output file to write data to
    * @param fileHeader an optional header to be appended (if any)
    * @param data data to be written to the file
    * @param fileFooter an optional footer to be appended (if any)
    */
  def writeToFile(filePath: String, fileHeader: Option[String], data: String, fileFooter: Option[String]): Unit = {
    val bw = IOUtils.getBufferedWriter(filePath) //new BufferedWriter(new FileWriter(filePath))
    try {
      if (fileHeader.isDefined)
        bw.append(fileHeader.get + "\n")
      bw.append(data)
      if (fileFooter.isDefined)
        bw.append("\n" + fileFooter.get)
    } catch {
      case e: IOException =>
        logger.error(s"Error while writing data to file - $filePath : " + e.getMessage, e)
    } finally {
      bw.close()
    }
  }

  def writeToFile(filePath: String, content: Iterator[String]): Unit = {
    val bw = IOUtils.getBufferedWriter(filePath)
    try {
      content.foreach(bw.append)
    } catch {
      case e: IOException =>
        logger.error(s"Error while writing data to file - $filePath", e)
    } finally {
      bw.close()
    }
  }

  /**
    * Writes data to the output file at specified path.
    * @param filePath path of the output file to write data to
    * @param fileHeader an optional header to be appended (if any)
    * @param data data to be written to the file
    * @param fileFooter an optional footer to be appended (if any)
    */
  def writeToFileJava(
    filePath: String,
    fileHeader: java.util.Optional[String],
    data: String,
    fileFooter: java.util.Optional[String]
  ): Unit = {
    val bw = IOUtils.getBufferedWriter(filePath) //new BufferedWriter(new FileWriter(filePath))
    try {
      if (fileHeader.isPresent)
        bw.append(fileHeader.get + "\n")
      bw.append(data)
      if (fileFooter.isPresent)
        bw.append("\n" + fileFooter.get)
    } catch {
      case e: IOException =>
        logger.error(s"Error while writing data to file - $filePath : " + e.getMessage, e)
    } finally {
      bw.close()
    }
  }

  def downloadAndUnpackIfNeeded(srcPath: String, remoteIfStartsWith: String = "http"): String = {
    val srcName = getName(srcPath)
    val srcBaseName = getBaseName(srcPath)

    val localPath =
      if (isRemote(srcPath, remoteIfStartsWith)) {
        val tmpPath = Paths.get(getTempDirectoryPath, srcName).toString
        downloadFile(srcPath, tmpPath)
        tmpPath
      } else if (isS3Remote(srcPath, "s3")) {
        val tmpPath = Paths.get(getTempDirectoryPath, srcName).toString
        downloadS3File(srcPath, tmpPath)
        tmpPath
      } else
        srcPath

    val unpackedPath =
      if (isZipArchive(localPath)) {
        val tmpPath = Paths.get(getTempDirectoryPath, srcBaseName).toString
        unzip(localPath, tmpPath, false)
        tmpPath
      } else
        localPath

    unpackedPath
  }

  def readAllLines(file: File): Seq[String] = {
    using(Source.fromFile(file.getPath)) { source =>
      source.getLines().toList
    }
  }

  def readAllLines(file: String): Seq[String] = {
    readAllLines(new File(file))
  }

  private def isZipArchive(sourceFilePath: String): Boolean = {
    assert(sourceFilePath != null)
    "zip".equalsIgnoreCase(getExtension(sourceFilePath))
  }

  private def isRemote(sourceFilePath: String, remoteIfStartsWith: String): Boolean = {
    assert(sourceFilePath != null)
    sourceFilePath.startsWith(remoteIfStartsWith)
  }

  private def isS3Remote(sourceFilePath: String, remoteIfStartsWith: String): Boolean = {
    assert(sourceFilePath != null)
    sourceFilePath.startsWith(remoteIfStartsWith)
  }

  /**
    * Reads files in parallel and returns all the loaded records as Iterable
    * @param dir the directory where the files reside
    * @param fileNamePattern glob file pattern
    * @param atMost the expected time interval for file reading
    * @param loader the function that actually read data from the reader
    * @tparam X the record type
    * @tparam M the container type
    * @return all the loaded records as an Iterable
    */
  def flatParRead[X, M[X] <: TraversableOnce[X]](dir: Path, fileNamePattern: String, atMost: Duration = 30 minutes)(
    loader: (Path, BufferedReader) => M[X]
  ): Iterable[X] =
    parRead(dir, fileNamePattern, atMost) { (path: Path, reader: BufferedReader) =>
      (path, loader(path, reader))
    }.values.flatten

  /**
    * Reads files in parallel and returns loaded data as a map containing each loaded file data as values
    * @param dir the directory where the files reside
    * @param fileNamePattern glob file pattern
    * @param atMost the expected time interval for file reading
    * @param loader the function that actually read data from the reader
    * @tparam Key the return map key
    * @tparam Value the the return map value
    * @return a Map containing the key values returned back by the loader
    */
  def parRead[Key, Value](dir: Path, fileNamePattern: String, atMost: Duration = 30 minutes)(
    loader: (Path, BufferedReader) => (Key, Value)
  ): Map[Key, Value] = {
    import scala.collection.JavaConverters._
    import scala.concurrent.ExecutionContext.Implicits._
    val directoryStream = Files.newDirectoryStream(dir, fileNamePattern)
    val fileList = directoryStream.iterator().asScala.toList
    if (fileList.isEmpty) {
      logger.info(s"No files $fileNamePattern found in directory '$dir'")
    }
    val futures = fileList
      .map { path: Path =>
        Future {
          using(IOUtils.getBufferedReader(path.toString)) { reader =>
            loader(path, reader)
          }
        }
      }
    Await.result(Future.sequence(futures), atMost).toMap
  }

  /**
    * Writes data to separate files in parallel
    * @param outputDir the ouput dir
    * @param fileNamePattern the file name pattern. It must contains $i which is substituted with the part number
    * @param numberOfParts the number of parts
    * @param atMost the expected time interval for file writing
    * @param saver the function that saves data to the provided writer.
    *              It takes part number (starting from 1), path to file and buffered writer as an input
    */
  def parWrite(outputDir: Path, fileNamePattern: String, numberOfParts: Int, atMost: Duration = 30 minutes)(
    saver: (Int, Path, BufferedWriter) => Unit
  ): Unit = {
    assert(numberOfParts > 0, "numberOfParts must be greater than zero")
    assert(fileNamePattern.contains("$i"), "fileNamePattern must contain $i for substitution")
    import scala.concurrent.ExecutionContext.Implicits._
    val fileList = (1 to numberOfParts)
      .map { i =>
        (i, Paths.get(outputDir.toString, fileNamePattern.replace("$i", i.toString)))
      }
    val futures = fileList.map {
      case (i: Int, path: Path) =>
        Future {
          using(IOUtils.getBufferedWriter(path.toString)) { writer =>
            saver(i, path, writer)
          }
        }
    }
    Await.result(Future.sequence(futures), atMost)
  }

  def gzipFile(file: String, deleteSourceFile: Boolean = false): Unit = {
    val filePath = Paths.get(file)
    if (!filePath.toFile.isFile) {
      throw new IllegalStateException(s"Not a file: `$file`")
    }

    using(IOUtils.getBufferedWriter(file, true)) { bw =>
      using(IOUtils.getBufferedReader(file)) { br =>
        io.IOUtils.copyLarge(br, bw)
      }
    }

    if (deleteSourceFile) {
      Files.deleteIfExists(filePath)
    }
  }

  /**
    * Not recursive (accepts only files)
    * @param out the output file path
    * @param files a sequence of pairs zip entry name -> file path
    */
  def zipFiles(out: String, files: IndexedSeq[(String, Path)]): String = {
    import java.io.{BufferedInputStream, FileInputStream, FileOutputStream}
    import java.util.zip.{ZipEntry, ZipOutputStream}

    val existed = files.filter { case (_, path)      => Files.exists(path) && Files.isRegularFile(path) }
    val notExited = files.filterNot { case (_, path) => Files.exists(path) && Files.isRegularFile(path) }
    notExited.foreach { case (name, _) => logger.error(s"Cannot find $name") }

    using(new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) { zip =>
      existed.foreach {
        case (name, path) =>
          zip.putNextEntry(new ZipEntry(name))
          using(new BufferedInputStream(new FileInputStream(path.toFile))) { in =>
            IOUtils.copyStream(in, zip)
          }
          zip.closeEntry()
      }
    }
    out
  }

  def getStreamFromZipFolder(pathToZip: String, fileName: String): Option[InputStream] = {
    val zipInputStream = new ZipInputStream(Files.newInputStream(new File(pathToZip).toPath))

    @tailrec
    def loop(maybeNext: Option[ZipEntry], result: Option[ZipEntry]): Option[ZipEntry] = {
      if (maybeNext.isEmpty) result
      else {
        Option(zipInputStream.getNextEntry) match {
          case Some(zipEntry) if zipEntry.getName == fileName =>
            loop(None, Some(zipEntry))
          case Some(_) => loop(Option(zipInputStream.getNextEntry), None)
          case None    => loop(None, result)
        }
      }
    }

    loop(Option(zipInputStream.getNextEntry), None) match {
      case Some(_) => Some(zipInputStream)
      case None =>
        org.apache.commons.io.IOUtils.closeQuietly(zipInputStream)
        None
    }
  }
}
