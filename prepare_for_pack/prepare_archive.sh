#!/bin/bash

gemini_path='production/sfbay/gemini'
rm -rf production/
mkdir -p $gemini_path
cp ../$gemini_path -R production/sfbay
echo 'folder content updated'

rm production.zip
echo 'old archive deleted'

zip -r production.zip production

