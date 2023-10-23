#!/bin/bash

bash_command="$*"
echo "Executing bash command:"
echo "$bash_command"
echo ""
eval "$bash_command"
echo ""
echo "Execution complete."

