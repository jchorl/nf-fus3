FROM golang:1 AS goofys

# pull in patch to https://github.com/kahing/goofys/issues/740
ARG GOOFYS_SHA=50c83de812c351c0689e1e72c950f2305769f518

# why not, they do it: https://github.com/kahing/goofys/blob/e903e56038fe0325e4538007b6acc064af8164c7/Makefile#L1
ENV CGO_ENABLED=0

WORKDIR /goofys
RUN wget -q -O - https://github.com/jchorl/goofys/archive/${GOOFYS_SHA}.tar.gz \
    | tar xzv --strip=1 \
    && go build -o /build/goofys .

FROM ubuntu:23.04

# ca-certs and fuse for goofys
# procps is a nextflow dependency
# groff, less (and glibc) are aws cli deps: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html
RUN apt-get -qq update && apt-get --no-install-recommends -qq install -y \
    ca-certificates \
    fuse \
    procps \
    less \
    groff \
    unzip \
    wget \
    && wget -q https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -O awscliv2.zip \
    && unzip -qq awscliv2.zip \
    && ./aws/install \
    && rm -rf awscliv2.zip \
    && apt-get -qq purge --autoremove -y unzip wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=goofys /build/goofys /usr/local/bin/goofys
