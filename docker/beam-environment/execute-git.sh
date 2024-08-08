#!/bin/bash

## ~15 MB
git config --global http.postBuffer 15728640
git config --global --add safe.directory /root/git

##
## working only if git volume was mounted
##
if [ -e "/root/git/.this_volume_was_not_mounted.txt" ]; then
  echo "/root/git volume was not mounted, please, in order to execute git command - mount the folder into /root/git"
  echo "here is the content of /root/git"
  ls -lah /root/git
  echo ""
else
  cd /root/git || echo "ERROR: path /root/git does not exist!"
  git_command="git $*"
  echo "Executing git command:"
  echo "$git_command"
  echo ""
  eval "$git_command"
  echo ""
  echo "Execution complete."
fi

