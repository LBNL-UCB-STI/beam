#!/bin/bash

pip install helics

src_path="/home/ubuntu/.local/lib/python2.7/site-packages/helics/capi.py"
src_entry="class _HelicsCHandle:"
src_entry_replacement="class _HelicsCHandle(object):"

#one last symbol less to grep
src_entry_to_grep="${src_entry%?}"

echo 'going to fix sources by replacing this line:'
echo "$(grep "$src_entry_to_grep" "$src_path")"

sed -i "/$src_entry/c\ $src_entry_replacement" "$src_path"

echo 'by this line:'
echo "$(grep "$src_entry_to_grep" "$src_path")"

echo 'done'