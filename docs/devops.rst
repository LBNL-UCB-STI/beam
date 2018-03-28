Setup Jenkins Server
====================

1.  From the AWS Management Console, launch the Amazon EC2 instance from an Amazon Machine Image (AMI) that has Ubuntu 64-bit as base operating system.

2.  Choose a security group that will allow SSH access as well as port 8080, 80 and 443 to access your Jenkins dashboard. You should only enable ingress from the IP addresses you wish to allow access to your server.

3.  Connect to the instance via SSH.

4.  Add oracle java apt repository::

    *$ sudo add-apt-repository ppa:webupd8team/java*

5.  Run commands to update system package index and install Java installer script::

    $ sudo apt update; sudo apt install oracle-java8-installer

6.  Add the repository key to the system.::

    $ wget -q -O - https://pkg.jenkins.io/debian/jenkins-ci.org.key \| sudo apt-key add - .

7.  Append the Debian package repository address to the server's sources.::

    $ echo deb https://pkg.jenkins.io/debian-stable binary/ \| sudo tee /etc/apt/sources.list.d/jenkins.list

8.  Run update so that apt-get will use the new repository::

    $ sudo apt-get update

9.  Install Jenkins and its dependencies, including Java::

    $ sudo apt-get install jenkins

10. Start Jenkins::

    $ sudo service jenkins start

11. Verify that it started successfully::

    $ sudo service jenkins status

12. If everything went well, the beginning of the output should show that the service is active and configured to start at boot:::

    jenkins.service - LSB: Start Jenkins at boot time
    Loaded: loaded (/etc/init.d/jenkins; bad; vendor preset: enabled)
    Active:active (exited) since Thu 2017-04-20 16:51:13 UTC; 2min 7s ago
    Docs: man:systemd-sysv-generator(8)

13. To set up installation, visit Jenkins on its default port, 8080, using the server domain name or IP address:
   http://ip_address_of_ec2_instance:8080

An "Unlock Jenkins" screen would appear, which displays the location of the initial password

|image0|

14. In the terminal window, use the cat command to display the password:::

   $ sudo cat /var/lib/jenkins/secrets/initialAdminPassword

15. Copy the 32-character alphanumeric password from the terminal and paste it into the "Administrator password" field, then click "Continue".

|image1|

16. Click the "Install suggested plugins" option, which will immediately begin the installation process:

|image2|

17. When the installation is complete, it prompt to set up the first administrative user. It's possible to skip this step and continue as admin using the initial password used above, but its batter to take a moment to create the user.

|image3|

18. Once the first admin user is in place, you should see a "Jenkins is ready!" confirmation screen.

|image4|

19. Click "Start using Jenkins" to visit the main Jenkins dashboard:

|image5|

At this point, Jenkins has been successfully installed.

20. Update your package lists and install Nginx:::

   $ sudo apt-get install nginx

21. To check successful installation run:::

   $ nginx -v

22. Move into the proper directory where you want to put your certificates::

   $ cd /etc/nginx

23. Generate a certificate::

   $ sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout /etc/nginx/cert.key -out /etc/nginx/cert.crt

24. Next you will need to edit the default Nginx configuration file.::

   $ sudo vi /etc/nginx/sites-enabled/default

25. Update the file with following contents::

   server {

    listen 80;

    return 301 https://$host$request_uri;

   }

   server {

    listen 443;
    server_name beam-ci.tk;

    ssl_certificate           /etc/nginx/cert.crt;
    ssl_certificate_key       /etc/nginx/cert.key;

    ssl on;
    ssl_session_cache  builtin:1000  shared:SSL:10m;
    ssl_protocols  TLSv1 TLSv1.1 TLSv1.2;
    ssl_ciphers HIGH:!aNULL:!eNULL:!EXPORT:!CAMELLIA:!DES:!MD5:!PSK:!RC4;
    ssl_prefer_server_ciphers on;

    access_log            /var/log/nginx/jenkins.access.log;

    location / {

      proxy_set_header        Host $host;
      proxy_set_header        X-Real-IP $remote_addr;
      proxy_set_header        X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header        X-Forwarded-Proto $scheme;

      # Fix the “It appears that your reverse proxy set up is broken" error.
      proxy_pass          http://localhost:8080;
      proxy_read_timeout  90;

      proxy_redirect      http://localhost:8080 https://beam-ci.tk;
    }
   }

26. For Jenkins to work with Nginx, we need to update the Jenkins config to listen only on the localhost interface instead of all (0.0.0.0), to ensure traffic gets handled properly. This is an important step because if Jenkins is still listening on all interfaces, then it will still potentially be accessible via its original port (8080).

27. Modify the /etc/default/jenkins configuration file to make these adjustments.::

   $ sudo vi /etc/default/jenkins

28. Locate the JENKINS\_ARGS line and update it to look like the following:::

   $ JENKINS_ARGS="--webroot=/var/cache/$NAME/war --httpListenAddress=127.0.0.1 --httpPort=$HTTP_PORT -ajp13Port=$AJP_PORT"

29. Then go ahead and restart Jenkins::

   $ sudo service jenkins restart

30. After that restart Nginx::

   $ sudo service nginx restart

You should now be able to visit your domain using either HTTP or HTTPS, and the Jenkins site will be served securely. You will see a certificate warning because you used a self-signed certificate.

31. Next we install certbot to setup nginx with as CA certificate. Certbot team maintains a PPA. Once you add it to your list of repositories all you'll need to do is apt-get the following packages:::

   $ sudo add-apt-repository ppa:certbot/certbot

32. Run apt update::

   $ sudo apt-get update

33. Install certbot for Nginx.::

   $ sudo apt-get install python-certbot-nginx

34. Get a certificate and have Certbot edit Nginx configuration automatically, run the following command.::

   $ sudo certbot –nginx

35. The Certbot packages on your system come with a cron job that will renew your certificates automatically before they expire. Since Let's Encrypt certificates last for 90 days, it's highly advisable to take advantage of this feature. You can test automatic renewal for your certificates by running this command:::

   $ sudo certbot renew –dry-run

36. Restart Nginx:::

   $ sudo service nginx restart

37. Go to AWS management console and update the Security Group associated with jenkins server by removing the port 8080, that we added in step 2.

.. |image0| image:: _static/figs/jenkins-unlock.png
.. |image1| image:: _static/figs/jenkins-customize.png
.. |image2| image:: _static/figs/jenkins-plugins.png
.. |image3| image:: _static/figs/jenkins-ready.png
.. |image4| image:: _static/figs/jenkins-first-admin.png
.. |image5| image:: _static/figs/jenkins-using.png
