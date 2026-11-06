#!/bin/sh
# Fetches the pictures that travel inside the APK, once, into app/src/main/assets.
#
# Run by hand and the result committed - not part of the build. A build that
# reaches the open internet is a build that fails when someone else's server is
# having a bad morning, and four shards fetching forty photographs each would
# empty an Unsplash demo quota (50 requests an hour) before the tests started.
# The same reasoning as tools/wx-fixtures.sh, which is also run by hand.
#
# Usage: NAVI_UNSPLASH_KEY=... sh tools/pics.sh
#
# The key is an Unsplash access key from https://unsplash.com/developers. It is
# only needed here: it does not go into the repository, and the pictures this
# writes need no key to be shown.
#
# The pictures are kept under the Unsplash License, which allows them to be used
# and redistributed, including inside an application, without permission. It
# does not require attribution; CREDITS.txt is written anyway, because the people
# who took them should be nameable.
set -eu

: "${NAVI_UNSPLASH_KEY:?set NAVI_UNSPLASH_KEY to an Unsplash access key}"

# 1280x720 is the head unit's screen, so nothing is scaled up and nothing is
# carried that cannot be seen. q=75 is where the file stops shrinking faster
# than it gets worse.
GEOMETRY="w=1280&h=720&fit=crop&fm=jpg&q=75"
PER_SET=20

root=$(cd "$(dirname "$0")/.." && pwd)
assets=$root/app/src/main/assets/pics

fetch_set() {
    set_name=$1
    query=$2
    dir=$assets/$set_name
    rm -rf "$dir"
    mkdir -p "$dir"

    echo "fetching $PER_SET pictures for $set_name ($query)"
    # One search request for the whole set, not one request per picture: the
    # quota is small and a page of thirty is one call.
    page=$(curl -sS -G https://api.unsplash.com/search/photos \
        --data-urlencode "query=$query" \
        --data-urlencode "per_page=30" \
        --data-urlencode "orientation=landscape" \
        -H "Authorization: Client-ID $NAVI_UNSPLASH_KEY" \
        -H "Accept-Version: v1")

    # Plain text handling rather than a JSON tool, so this runs on a machine
    # with nothing installed: the raw urls and the names, in the order the
    # search returned them.
    echo "$page" | tr ',' '\n' | grep -o '"raw":"[^"]*"' | cut -d'"' -f4 \
        | sed 's/\\u0026/\&/g' > "$dir/.urls"
    echo "$page" | tr '{' '\n' | grep -o '"name":"[^"]*"' | cut -d'"' -f4 > "$dir/.names"

    found=$(wc -l < "$dir/.urls" | tr -d ' ')
    if [ "$found" -lt "$PER_SET" ]; then
        echo "only $found pictures came back for $query; wanted $PER_SET" >&2
        exit 1
    fi

    i=1
    while [ "$i" -le "$PER_SET" ]; do
        url=$(sed -n "${i}p" "$dir/.urls")
        who=$(sed -n "${i}p" "$dir/.names")
        name=$(printf '%02d' "$i").jpg
        curl -sS -o "$dir/$name" "$url&$GEOMETRY"
        echo "$set_name/$name  $who" >> "$assets/CREDITS.txt"
        i=$((i + 1))
    done
    rm -f "$dir/.urls" "$dir/.names"
}

mkdir -p "$assets"
printf 'Pictures bundled with the launcher, from Unsplash, under the Unsplash License.\nFetched by tools/pics.sh.\n\n' > "$assets/CREDITS.txt"

fetch_set lego "lego star wars"
fetch_set kitty "kitten"

echo
du -sh "$assets"
ls -la "$assets"/lego | head -3
