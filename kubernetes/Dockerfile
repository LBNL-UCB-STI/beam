########################################################
# The FUSE driver needs elevated privileges, run Docker with --privileged=true
########################################################

FROM alpine:3.8

ENV S3_BUCKET ''
ENV MNT_POINT /data
ENV S3_REGION ''
ENV AWS_KEY ''
ENV AWS_SECRET_KEY ''

RUN apk --update --no-cache add fuse alpine-sdk automake autoconf rsync libxml2-dev fuse-dev curl-dev git bash g++ wget make && \
    git clone https://github.com/s3fs-fuse/s3fs-fuse.git /tmp/s3fs-fuse && \
    cd /tmp/s3fs-fuse && ./autogen.sh && ./configure && make && make install

RUN mkdir -p "$MNT_POINT"

RUN apk del wget automake g++ git make && \
    rm -rf /var/cache/apk/* /var/tmp/*

CMD echo "${AWS_KEY}:${AWS_SECRET_KEY}" > /etc/passwd-s3fs && \
    chmod 600 /etc/passwd-s3fs && \
    exec /usr/local/bin/s3fs $S3_BUCKET $MNT_POINT -f -o endpoint=${S3_REGION},endpoint=us-west-2,allow_other,use_cache=/tmp,max_stat_cache_size=1000,stat_cache_expire=900,retries=5,connect_timeout=10,umask=0000 -o nonempty