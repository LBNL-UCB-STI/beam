package beam.utils.google_routes_db.sql

import java.sql.PreparedStatement

/** Type class that supports mapping of an item over PreparedStatement. */
trait PSMapping[T] {
  def mapPrepared(item: T, ps: PreparedStatement): Unit
}

object PSMapping {

  implicit class PSMappingOps[T: PSMapping](item: T) {

    def mapPrepared(ps: PreparedStatement): Unit = {
      implicitly[PSMapping[T]].mapPrepared(item, ps)
    }
  }
}
