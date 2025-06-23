#!/usr/bin/bash

# update fcitx5-rime
echo "updating fcitx5-rime"
pushd plugin/rime/src/main/cpp/fcitx5-rime
git remote add gh https://github.com/fxliang/fcitx5-rime.git || git remote set-url gh https://github.com/fxliang/fcitx5-rime.git
git fetch -v gh master
git checkout gh/master
popd
sed -i 's|/fcitx/|/fxliang/|g' plugin/rime/licenses/libraries/fcitx5-rime.json

# update prebuilt
echo "updating prebuilt"
pushd lib/fcitx5/src/main/cpp/prebuilt
git remote add gh https://github.com/fxliang/prebuilt.git || git remote set-url gh https://github.com/fxliang/prebuilt.git
git fetch -v gh master
git checkout gh/master
popd
