#!/usr/bin/env bash
#
# Regenerates the canned Open-Meteo readings in tools/wx/.
#
# They are committed, because three things read them and all three must see the
# same weather: the warning unit tests, the instrumented tests and the
# screenshot script. The timestamps are fixed on purpose, so a screenshot taken
# today looks exactly like one taken next year.
#
set -euo pipefail

OUT="$(cd "$(dirname "$0")" && pwd)/wx"
mkdir -p "$OUT"

DAY=2026-01-15
NOW_HOUR=07

# hours <value> [override-hour] [override-value]
# Builds a 24-entry hourly array, optionally with one differing hour.
hours() {
    local value="$1" at="${2:-}" other="${3:-}" out="" h
    for h in $(seq 0 23); do
        local v="$value"
        if [ -n "$at" ] && [ "$h" = "$at" ]; then v="$other"; fi
        out="$out${out:+,}$v"
    done
    printf '%s' "$out"
}

# The shape of a day: coldest before dawn, warmest mid-afternoon. Added to the
# base temperature so the forecast strip reads like a forecast rather than a
# flat line, while staying far enough from the thresholds not to change which
# warnings the fixtures raise.
CURVE=(-3 -3.5 -4 -4 -3.5 -3 -2 -1 0 1 2 2.5 3 3.5 3.5 3 2 1 0 -1 -1.5 -2 -2.5 -3)

# temperatures <base>
temperatures() {
    local base="$1" out="" h
    for h in $(seq 0 23); do
        out="$out${out:+,}$(awk "BEGIN{printf \"%.1f\", $base + ${CURVE[$h]}}")"
    done
    printf '%s' "$out"
}

times() {
    local out="" h
    for h in $(seq -w 0 23); do
        out="$out${out:+,}\"$DAY" && out="${out}T${h}:00\""
    done
    printf '%s' "$out"
}

# write <name> <place> <temp> <code> <gusts> <vis> <snow> <precip>
#       [h-code-hour] [h-code] [h-temp]
write() {
    local name="$1" place="$2" temp="$3" code="$4" gusts="$5" vis="$6" snow="$7" precip="$8"
    local hcHour="${9:-}" hcValue="${10:-}" hTemp="${11:-$3}"
    cat > "$OUT/$name.json" <<JSON
{
  "place": "$place",
  "current": {
    "time": "${DAY}T${HOUR:-$NOW_HOUR}:00",
    "temperature_2m": $temp,
    "apparent_temperature": $(awk "BEGIN{printf \"%.1f\", $temp - 2.5}"),
    "weather_code": $code,
    "is_day": ${IS_DAY:-1},
    "wind_speed_10m": $(awk "BEGIN{printf \"%.0f\", $gusts * 0.6}"),
    "wind_gusts_10m": $gusts
  },
  "hourly": {
    "time": [$(times)],
    "temperature_2m": [$(temperatures "$hTemp")],
    "weather_code": [$(hours "$code" "$hcHour" "$hcValue")],
    "precipitation_probability": [$(hours "$precip")],
    "wind_gusts_10m": [$(hours "$gusts")],
    "visibility": [$(hours "$vis")],
    "snowfall": [$(hours "$snow")]
  },
  "daily": {
    "time": ["$DAY", "2026-01-16", "2026-01-17", "2026-01-18"],
    "weather_code": [$code, 3, 71, 1],
    "temperature_2m_max": [$(awk "BEGIN{printf \"%.1f\", $temp + 2}"), 4.0, -1.0, 2.0],
    "temperature_2m_min": [$(awk "BEGIN{printf \"%.1f\", $temp - 4}"), -2.0, -6.0, -3.0],
    "precipitation_probability_max": [$precip, 20, 80, 10]
  }
}
JSON
    echo "$OUT/$name.json"
}

#      name      place      temp  code gusts   vis   snow precip  [hour code temp]
write calm      "Warszawa"   8.0    1    20  20000   0.0     5
write ice       "Warszawa"   1.5    3    25  18000   0.0    10
write icerain   "Warszawa"   6.0    1    30  15000   0.0    60   9  66  4.0
write storm     "Warszawa"  18.0   95    45  12000   0.0    90
write wind      "Warszawa"  12.0    3    85  20000   0.0    20
write fog       "Warszawa"   6.0   45    15    120   0.0    10
write snow      "Gdansk"    -2.0   75    40   3000   2.0    85
write all       "Gdansk"    -1.0   95    90     90   3.0    95

# And one after dark. It is the only fixture that draws the moon, and the moon
# shipped broken through 2.4 - a lopsided ring with a chord across it - because
# every other reading here is daytime and nothing ever rendered it.
IS_DAY=0 HOUR=21 write night "Warszawa"   3.0    1    15  20000   0.0     5
