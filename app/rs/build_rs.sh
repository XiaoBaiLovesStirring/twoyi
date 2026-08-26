#! /bin/bash

#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#

# Source cargo environment
if [ -f "$HOME/.cargo/env" ]; then
    . "$HOME/.cargo/env"
fi

export ANDROID_NDK_HOME=/opt/android-sdk/ndk/23.2.8568313

cargo xdk -t arm64-v8a -o ../src/main/jniLibs build $1
