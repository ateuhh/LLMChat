FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

# -------- Android SDK env --------
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0

# -------- Базовые пакеты --------
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl unzip zip ca-certificates git procps libstdc++6 zlib1g \
    adb file binutils \
 && rm -rf /var/lib/apt/lists/*

# -------- commandline-tools --------
RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    cd /tmp && \
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o cmdtools.zip && \
    unzip -q cmdtools.zip && rm cmdtools.zip && \
    mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    mkdir -p /root/.android && touch /root/.android/repositories.cfg

# -------- Accept licenses --------
RUN mkdir -p $ANDROID_HOME/licenses && \
    echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" >  $ANDROID_HOME/licenses/android-sdk-license && \
    echo "d56f5187479451eabf01fb78af6dfcb131a6481e" >> $ANDROID_HOME/licenses/android-sdk-license && \
    echo "84831b9409646a918e30573bab4c9c91346d8abd" >  $ANDROID_HOME/licenses/google-gdk-license

# -------- Optional diagnostics --------
RUN sdkmanager --sdk_root=$ANDROID_HOME --list || true

# -------- Minimal SDK components --------
RUN yes | sdkmanager --sdk_root=$ANDROID_HOME \
    "platform-tools" \
    "platforms;android-30" \
    "build-tools;34.0.0"

WORKDIR /workspace

COPY docker/android/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
