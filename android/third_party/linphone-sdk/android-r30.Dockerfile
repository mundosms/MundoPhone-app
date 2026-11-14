# Entorno de compilación de linphone-sdk 5.5.x para Android.
#
# Copia fijada de linphone-sdk/docker-files/bc-dev-android-r30, el Dockerfile de la imagen
# bc-dev-android-r30:20260914_update_ndk con la que Belledonne compila la 5.5.36 (.gitlab-ci-files/android/builds.yml).
# No se usa su imagen porque su registro (gitlab.linphone.org:4567) no responde desde aquí (22/10/2026).
# Diferencias, a propósito:
#   - base fijada por digest;
#   - NDK y cmdline-tools copiados de ~/.cache/mundophone-sdk-build/dl y comprobados por sha256 (sin wget en la
#     construcción);
#   - sin el usuario bc con contraseña y sudo: corre como root y el guion devuelve los ficheros al usuario.
#
# Construir (el contexto es la carpeta de descargas):
#   docker build -f third_party/linphone-sdk/android-r30.Dockerfile \
#     -t mundophone/linphone-sdk-android:r30-20261022 ~/.cache/mundophone-sdk-build
FROM debian:12@sha256:854dd8ab51d62866dbd8f5431d15ce77b12983a2a4a1e1fe43a2395dc7ef9f61

RUN apt-get update && \
    apt-get install -y locales && \
    echo "en_US.UTF-8 UTF-8" > /etc/locale.gen && \
    locale-gen && \
    apt-get clean
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8' SHELL=/bin/bash

RUN apt-get update && \
    apt-get install -y curl unzip rsync \
        ccache doxygen git make cmake nasm ninja-build meson perl openssh-client pkg-config yasm \
        openjdk-17-jdk-headless \
        python3 python3-pip python3-six python3-pystache python3-graphviz && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENV ANDROID_NDK=r30 ANDROID_HOME=/opt/android-sdk-linux
ENV ANDROID_NDK_HOME=/opt/android-ndk-r30
ENV PATH=$ANDROID_NDK_HOME:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

COPY dl/SHA256SUMS dl/android-ndk-r30-linux.zip dl/commandlinetools-linux-9477386_latest.zip /tmp/dl/
RUN cd /tmp/dl && sha256sum -c SHA256SUMS && \
    unzip -q android-ndk-r30-linux.zip -d /opt && \
    mkdir -p $ANDROID_HOME/cmdline-tools/latest && \
    unzip -q commandlinetools-linux-9477386_latest.zip && \
    cp -R cmdline-tools/* $ANDROID_HOME/cmdline-tools/latest/ && \
    cd / && rm -rf /tmp/dl

# Sin el paquete "tools" de su Dockerfile: Google lo ha retirado («Failed to find package 'tools'») y lo sustituyen las
# cmdline-tools de arriba.
RUN echo "y" | sdkmanager "platform-tools" "platforms;android-34" && \
    yes | sdkmanager --licenses > /dev/null && \
    chmod -R ugo+rwx $ANDROID_HOME

WORKDIR /src
CMD ["bash"]
