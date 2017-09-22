package tech.sourced.api.provider

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

import org.apache.commons.io.FileUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.input.PortableDataStream
import org.apache.spark.internal.Logging
import org.eclipse.jgit.lib.{Repository, RepositoryBuilder}
import tech.sourced.api.util.MD5Gen
import tech.sourced.siva.SivaReader

import scala.collection.JavaConverters._
import scala.collection.concurrent

class RepositoryProvider(val localPath: String) extends Logging {
  private var skipCleanup = false

  private val repositories: concurrent.Map[String, Repository] =
    new ConcurrentHashMap[String, Repository]().asScala

  def get(conf: Configuration, path: String): Repository = synchronized {
    repositories.get(path) match {
      case Some(repo) => repo
      case None => {
        val repo = genRepository(conf, path, localPath)
        repositories.put(path, repo)
        repo
      }
    }
  }

  def get(pds: PortableDataStream): Repository =
    this.get(pds.getConfiguration, pds.getPath())

  def close(path: String): Unit =
    repositories.get(path).foreach(r => {
      r.close()
      // TODO maybe others are using this repository instance
      // FileUtils.deleteQuietly(r.getDirectory)
    })

  private[provider] def genRepository(conf: Configuration, path: String, localPath: String): Repository = {
    val remotePath = new Path(path)

    val localCompletePath =
      new Path(localPath,
        new Path(RepositoryProvider.temporalLocalFolder,
          new Path(MD5Gen.str(path), remotePath.getName)
        )
      )

    val localSivaPath = new Path(localPath, new Path(RepositoryProvider.temporalSivaFolder, remotePath.getName))
    val fs = FileSystem.get(conf)

    if (!fs.exists(localSivaPath)) {
      // Copy siva file to local fs
      log.debug(s"Copy $remotePath to $localSivaPath")
      fs.copyToLocalFile(remotePath, localSivaPath)
    }

    if (!fs.exists(localCompletePath)) {
      // unpack siva file
      val sr = new SivaReader(new File(localSivaPath.toString))
      val index = sr.getIndex.getFilteredIndex.getEntries.asScala
      index.foreach(ie => {
        val e = sr.getEntry(ie)
        val outPath = Paths.get(localCompletePath.toString, ie.getName)

        FileUtils.copyInputStreamToFile(e, new File(outPath.toString))
      })
    }

    // After copy create a repository instance using the local path
    val repo = new RepositoryBuilder().setGitDir(new File(localCompletePath.toString)).build()

    // delete siva file
    if (!skipCleanup) {
      log.debug(s"Delete $localSivaPath")
      FileUtils.deleteQuietly(Paths.get(localSivaPath.toString).toFile)
    }

    repo
  }

}

object RepositoryProvider {
  var provider: RepositoryProvider = _

  def apply(localPath: String, skipCleanup: Boolean = false): RepositoryProvider = {
    if (provider == null) {
      provider = new RepositoryProvider(localPath)
    }
    provider.skipCleanup = skipCleanup

    if (provider.localPath != localPath) {
      throw new RuntimeException(s"actual provider instance is not intended " +
        s"to be used with the localPath provided: $localPath")
    }

    provider
  }

  val temporalLocalFolder = "processing-repositories"
  val temporalSivaFolder = "siva-files"
}
