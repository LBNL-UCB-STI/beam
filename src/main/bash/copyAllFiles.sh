sudo chmod 600 ~/.ssh/result_host_cert.pem


if [ -z "$(ls -A $1)" ]; then
   echo "Empty"
   exit 0
else
   echo "Not Empty"
   echo "$(du -sh $1)"
fi

echo "Copying the files..."
sudo scp -i ~/.ssh/result_host_cert.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -r $1 ubuntu@$2:~/sigoptResults/
echo "Copying completed..."
echo "Moving the suggestions to one level up"
sudo mv $1/* $1/../
echo "Moved the suggestions"
echo "Done.."