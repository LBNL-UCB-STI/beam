package beam

import java.security.Permission

class NoExitForSpecSecurityManager extends SecurityManager {

  override def checkExit(status: Int): Unit = {
    if (status != 0) {
      throw new SecurityException(s"System.exit() called with status: $status")
    }
    // Allow exit status 0 (normal termination)
  }

  // Grant all other permissions (required to avoid AccessControlException during logging, etc.)
  override def checkPermission(perm: Permission): Unit = {}
  override def checkPermission(perm: Permission, context: Object): Unit = {}
}
