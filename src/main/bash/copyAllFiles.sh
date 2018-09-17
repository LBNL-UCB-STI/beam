sudo chmod 600 ~/.ssh/result_host_cert.pem
sudo scp -i ~/.ssh/result_host_cert.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -r $1 ubuntu@$2:~/sigoptResults/ && sudo mv $1/* $1/../
