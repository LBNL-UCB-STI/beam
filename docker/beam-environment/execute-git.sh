#!/bin/bash

## ~15 MB
git config --global http.postBuffer 15728640
git config --global --add safe.directory /app/git

##
## working only if git volume was mounted
##
if [ -e "/app/git/.this_volume_was_not_mounted.txt" ]; then
  echo "/app/git volume was not mounted, please, in order to execute git command - mount the folder into /app/git"
  echo "here is the content of /app/git"
  ls -lah /app/git
  echo ""
else
  cd /app/git || echo "ERROR: path /app/git does not exist!"
  git_command="git $*"
  echo "Executing git command:"
  echo "$git_command"
  echo ""
  eval "$git_command"
  echo ""
  echo "Execution complete."
fi

