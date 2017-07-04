#!/bin/bash

# This code is licensed under the MIT software license
# This header information may not be removed
# This source code is provided as is; with no warranty implied or otherwise
# copyright IceBurst 2017 member of the Burst Development Team
#   v.1b - Initial release, Ubuntu tool

GIT=`which git`
JAVA=`which javac`
OS=`uname -a`
CWD=`pwd`

# Check for all dependancies and get as required

if [[ $UID != 0 ]]; then
  echo "please rerun the command with sudo ex: sudo ${0}"
exit 1
fi

# remove any old version
rm -rf ${CWD}/burstwallet

if [[ "$OS" == *"Ubuntu"* ]]
then
echo "Determined this is Ubuntu, proceeding..."

USER=`logname`

echo;

if [[ -z ${GIT} ]]
then
  echo "Installing Git..."
  apt install -qq git
fi

if [[ -z ${JAVA} ]]
then
  echo "Installing JAVA Components..."
  apt install -qq default-jre
fi

# Get and Build the Burst Wallet
git clone https://github.com/burst-team/burstcoin burstwallet
cd ${CWD}/burstwallet
./compile.sh
# modify some constants
sed -i 's@rebroadcastAfter=4@rebroadcastAfter=12@' ${CWD}/burstwallet/conf/nxt-default.properties
sed -i 's@rebroadcastEvery=2@rebroadcastEvery=6@' ${CWD}/burstwallet/conf/nxt-default.properties

cd ${CWD}
chown -R ${USER}:${USER} ${CWD}/burstwallet

#Setup all the services
JAVA=`which java`
rm -f /etc/systemd/system/burstwallet.service
cat >> /etc/systemd/system/burstwallet.service << WALLET
Description=Burstwallet
After=network-online.target
Requires=network-online.target

[Service]
User=${USER}
WorkingDirectory=${CWD}/burstwallet
ExecStart=${JAVA} -cp burst.jar:lib/*:conf nxt.Nxt
Restart=always
WALLET

systemctl daemon-reload
systemctl enable burstwallet.service
systemctl start burstwallet.service
systemctl status burstwallet.service

echo "Installation finished!"
echo "Access your wallet at http://localhost:8125"
echo;

fi

