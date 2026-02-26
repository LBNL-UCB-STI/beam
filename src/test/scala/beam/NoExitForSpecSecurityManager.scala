package beam

import java.security.Permission

/**
 * Security manager that intercepts System.exit(10) calls.
 *
 * Status 10 is thrown in:
 *   src/main/scala/beam/agentsim/scheduler/BeamAgentScheduler.scala
 *   within the `SimulationStuckCheck` function.
 *
 * This indicates that the simulation detected a deadlock/stuck condition
 * and attempted to exit the JVM. In tests, we want to catch this immediately,
 * dump the list of running tests, and fail the build with a clear banner.
 */
class NoExitForSpecSecurityManager extends SecurityManager {

  override def checkExit(status: Int): Unit = {
    if (status == 10) {
      // Print unmistakable banner and currently running tests
      System.err.println("\n" + "=" * 80)
      System.err.println("!!! DETECTED System.exit(10) CALL !!!")
      System.err.println("(Indicates simulation stuck condition – see BeamAgentScheduler.scala)")
      System.err.println("Tests still running at the moment of exit:")
      TestTracker.getRunningTests.foreach { testId =>
        System.err.println(s"  - $testId")
      }

      System.err.println("\nStack trace:")
      Thread.currentThread.getStackTrace.drop(2).foreach { elem =>
        System.err.println(s"    at $elem")
      }
      System.err.println("=" * 80 + "\n")

      // Halt immediately – recursive checkExit(1) is ignored because status != 10
      Runtime.getRuntime.halt(1)
    }
    // For any other status, do nothing – normal exit proceeds
  }

  // Grant all permissions to avoid AccessControlException from logging frameworks
  override def checkPermission(perm: Permission): Unit = {}
  override def checkPermission(perm: Permission, context: Object): Unit = {}
}