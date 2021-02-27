#!/bin/bash

# cross-platform version of gnu 'readlink -f'
realpath=$(python -c 'import os; import sys; print (os.path.realpath(sys.argv[1]))' "$0")
bin=`dirname "$realpath"`
bin=`cd "$bin">/dev/null; pwd`

usage () {
   echo "Usage: `basename $0` -r <releaseVersion> -n <newVersion>"
   echo ""
}

# read the options
OPTS=$(getopt -s bash --option r:n:h --longoptions release-version:,new-version:,help -n `basename "$0"` -- "$@")
eval set -- "$OPTS"

RELEASE_VERSION=""
NEW_VERSION=""

# extract options and their arguments into variables.
while true ; do
  case "$1" in
    -r|--release-version) RELEASE_VERSION="$2"; shift 2 ;;
    -n|--new-version) NEW_VERSION="$2"; shift 2 ;;
    -h|--help) usage; exit 1 ;;
    --) shift; break ;;
    *) echo "Given command options are invalid." >&2 ; usage; exit 1 ;;
  esac
done

if [ -z $RELEASE_VERSION ] || [ -z $NEW_VERSION ]; then
  echo "Release version and New Version MUST be specified."
  usage
  exit 1
fi

echo "Releasing with the version \"$RELEASE_VERSION\"."
echo "Setting the new version to \"$NEW_VERSION\""
./gradlew release -Prelease.useAutomaticVersion=true -Prelease.releaseVersion=$RELEASE_VERSION -Prelease.newVersion=$NEW_VERSION
