# Deployment to AWS

Since this lambda function has external dependencies it has to be deployed in a zip archive.

To do that

1. `cd aws/src/main/python/spreadsheetData`
1. `pip3 install -r requirements.txt --target ./package`
1. ```
    cd package
    zip -r ../my-deployment-package.zip .
   ```
2. ```
   cd ..
   zip my-deployment-package.zip lambda_function.py
   ```

Then upload the resulting zip archive via AWS Console UI ( Code / Upload from / .zip file)

Instruction was taken from:
https://docs.aws.amazon.com/lambda/latest/dg/python-package.html