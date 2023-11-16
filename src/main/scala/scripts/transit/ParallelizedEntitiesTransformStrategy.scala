package scripts.transit

import org.onebusaway.gtfs.services.GtfsMutableRelationalDao
import org.onebusaway.gtfs_transformer.`match`.{EntityMatch, TypedEntityMatch}
import org.onebusaway.gtfs_transformer.collections.{IdKey, IdKeyMatch}
import org.onebusaway.gtfs_transformer.factory.EntitiesTransformStrategy
import org.onebusaway.gtfs_transformer.services.{EntityTransformStrategy, TransformContext}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ParallelizedEntitiesTransformStrategy extends EntitiesTransformStrategy {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private val _modificationsByType: collection.mutable.Map[Class[_], ArrayBuffer[MatchAndTransform]] =
    new collection.mutable.HashMap[Class[_], ArrayBuffer[MatchAndTransform]]

  override def addModification(entityMatch: TypedEntityMatch, modification: EntityTransformStrategy): Unit = {
    val modifications = getModificationsForType(entityMatch.getType, _modificationsByType)
    modifications += new MatchAndTransform(entityMatch.getPropertyMatches, modification)
  }

//  override def getTransformsForType(entityType: Class[_]): ArrayBuffer[MatchAndTransform] = {
//    _modificationsByType.getOrElse(entityType, ArrayBuffer.empty[MatchAndTransform])
//  }

  override def run(context: TransformContext, dao: GtfsMutableRelationalDao): Unit = {
    _modificationsByType.foreach { case (entityType, modifications) =>
      if (classOf[IdKey].isAssignableFrom(entityType)) {
        modifications.foreach { pair =>
          val entityMatch = pair.entityMatch.asInstanceOf[IdKeyMatch]
          pair.transform.run(context, dao, entityMatch.getKey)
        }
      } else {
        val entities = dao.getAllEntitiesForType(entityType).asScala
        val futures = entities.map { entity =>
          Future {
            modifications.foreach { pair =>
              if (pair.entityMatch.isApplicableToObject(entity)) {
                pair.transform.run(context, dao, entity)
              }
            }
          }
        }
        Future.sequence(futures).onComplete {
          case Success(_)         => println("All transformations completed successfully.")
          case Failure(exception) => println(s"Error occurred: ${exception.getMessage}")
        }
      }
    }
  }

  private def getModificationsForType(
    classType: Class[_],
    m: collection.mutable.Map[Class[_], ArrayBuffer[MatchAndTransform]]
  ): ArrayBuffer[MatchAndTransform] = m.getOrElseUpdate(classType, new ArrayBuffer[MatchAndTransform]())

  class MatchAndTransform(val entityMatch: EntityMatch, val transform: EntityTransformStrategy)
}
