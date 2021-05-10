package scripts.s3wrapper

import scala.annotation.tailrec

sealed trait S3RemoteResource {
  def key: String
  def sizeInBytes: Long
}

case class S3RemoteFile(key: String, sizeInBytes: Long) extends S3RemoteResource

case class S3RemoteDirectory(key: String, children: Set[S3RemoteResource] = Set.empty) extends S3RemoteResource {
  require(key.endsWith("/"), "S3RemoteDirectory key must ends with slash(/)")

  @tailrec
  final def findResource(resourceKey: String): Option[S3RemoteResource] = {
    if (key == resourceKey) {
      Some(this)
    } else {
      children.find { c =>
        resourceKey.contains(c.key)
      } match {
        case Some(value: S3RemoteDirectory) =>
          value.findResource(resourceKey)
        case Some(value: S3RemoteFile) if value.key == resourceKey =>
          Some(value)
        case _ =>
          None
      }
    }
  }

  def allKeysRecursively: Set[String] = {
    flattenChildrenAsResource.map(_.key)
  }

  private def flattenChildrenAsResource: Set[S3RemoteResource] = {
    children.foldLeft(Set.empty[S3RemoteResource]) {
      case (acc: Set[S3RemoteResource], child: S3RemoteFile) => acc + child
      case (acc: Set[S3RemoteResource], child: S3RemoteDirectory) => acc ++ child.flattenChildrenAsResource
    } + this
  }

  override lazy val sizeInBytes: Long = children.map(_.sizeInBytes).sum

}

object S3RemoteDirectory {
  def apply(key: String, children: Seq[S3RemoteResource]): S3RemoteDirectory = {
    S3RemoteDirectory(key, children.toSet)
  }
}
