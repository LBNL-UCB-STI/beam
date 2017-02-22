package beam.metasim

import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, Suite}

/**
  * Created by sfeygin on 2/20/17.
  */
object TestUtils {

  trait StopSystemAfterAll extends BeforeAndAfterAll{
    this: TestKit with Suite =>
    override protected def afterAll(): Unit = {
      super.afterAll()
      system.terminate()
    }
  }

}
