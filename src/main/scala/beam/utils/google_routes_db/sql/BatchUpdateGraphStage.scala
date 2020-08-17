package beam.utils.google_routes_db.sql

import java.sql.{Connection, PreparedStatement}
import javax.sql.DataSource

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}

import scala.collection.immutable.Seq
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

import PSMapping._

/**
 * A GraphStage where
 * - Input and output element is a Seq[A]
 * - Logic performs batch update according to SQL statement and PSMapping
 *
 * Input Seq is inserted as a batch, pushing same Seq to output.
 */
class BatchUpdateGraphStage[A : PSMapping](
  private val dataSource: DataSource,
  private val psCreator: Connection ⇒ PreparedStatement
) extends GraphStageWithMaterializedValue[
            FlowShape[Seq[A], Seq[A]],
            Future[BatchUpdateGraphStage.Result]] {

  private val in: Inlet[Seq[A]] = Inlet[Seq[A]]("BatchUpdate.in")
  private val out: Outlet[Seq[A]] = Outlet[Seq[A]]("BatchUpdate.out")

  override def shape: FlowShape[Seq[A], Seq[A]] = FlowShape.of(in, out)

  override def createLogicAndMaterializedValue(
    inheritedAttributes: Attributes
  ): (GraphStageLogic, Future[BatchUpdateGraphStage.Result]) = {

    // Materialized value, completed when the flow is completed or failed
    val promise = Promise[BatchUpdateGraphStage.Result]

    val logic: GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {

        val (con, ps): (Connection, PreparedStatement) = {
          try {
            val con0 = dataSource.getConnection()
            con0.setAutoCommit(false)
            val ps0 = psCreator(con0)
            (con0, ps0)
          } catch {
            case e: Throwable ⇒
              failStage(e)
              (null, null)
          }
        }

        var batchesProcessed, itemsProcessed, rowsUpdated: Int = 0

        setHandler(in, this)
        setHandler(out, this)

        //
        // Flow methods
        //

        override def onUpstreamFinish(): Unit = commitAndComplete()

        override def onUpstreamFailure(e: Throwable): Unit = {
          rollbackQuietly()
          failWithException(e)
        }

        override def onDownstreamFinish(cause: Throwable): Unit =
          if (cause == null) commitAndComplete()
          else failWithException(cause)

        //
        // Private methods
        //

        private def commitAndComplete(): Unit = Try { con.commit() } match {
          case Success(_) ⇒ completeSuccessfully()
          case Failure(e) ⇒ failWithException(e)
        }

        private def completeSuccessfully(): Unit = {
          closeQuietly()
          promise.complete(
            Success(
              BatchUpdateGraphStage.Result(
                batchesProcessed,
                itemsProcessed,
                rowsUpdated
              )
            )
          )
          completeStage()
        }

        private def failWithException(e: Throwable): Unit = {
          closeQuietly()
          promise.failure(e)
          failStage(e)
        }

        private def rollbackQuietly(): Unit = {
          try {
            con.rollback()
          } catch {
            case ex: Throwable ⇒
              log.error(ex, "Rollback failure")
          }
        }

        private def closeQuietly(): Unit = {
          try {
            con.close()
          } catch {
            case e: Throwable ⇒
              log.error(e, "Close failure")
          }
        }

        override def onPush(): Unit = {
          val batch: Seq[A] = grab(in)
          val batchSize = batch.size
          if (batchSize > 0) {
            try {
              batch.foreach { item ⇒
                item.mapPrepared(ps)
                ps.addBatch()
              }
              val updated = ps.executeBatch()
              val updatedSum = updated.sum
              log.debug(s"Batch: size=$batchSize updated=$updatedSum")
              batchesProcessed += 1
              itemsProcessed += batchSize
              rowsUpdated += updatedSum
              push(out, batch)
            } catch {
              case e: Throwable ⇒ failWithException(e)
            }
          } else {
            log.debug("Empty batch")
            push(out, batch)
          }
        }

        override def onPull(): Unit = pull(in)

      }

    (logic, promise.future)
  }
}

object BatchUpdateGraphStage {

  case class Result(
    batchesProcessed: Int,
    itemsProcessed: Int,
    rowsUpdated: Int
  )
}

