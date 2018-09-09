export TOZIP=$1
tar -zcvf "$TOZIP.tar.gz" $TOZIP
sudo aws --region us-east-2 s3 cp "$TOZIP.tar.gz" "s3://beam-outputs/$2/"
