package beam

import java.security.Permission

class NoExitForSpecSecurityManager extends SecurityManager {

  override def checkExit(status: Int): Unit = {
    throw new SecurityException(s"System.exit() called with status: $status")
  }

  // Allow all permissions – single‑argument version
  override def checkPermission(perm: Permission): Unit = {}

  // Allow all permissions – two‑argument version (called with a context)
  override def checkPermission(perm: Permission, context: Object): Unit = {}
}
