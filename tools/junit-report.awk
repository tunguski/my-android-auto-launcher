# Turns Gradle's JUnit XML into something a reader without credentials can see.
#
#   awk -f tools/junit-report.awk -v mode=annotate  <xml files>
#   awk -f tools/junit-report.awk -v mode=table     <xml files>
#
# Why it exists. On a public repository the job log and the uploaded artifacts
# both need an authenticated reader, so the only thing visible from outside a
# failed run is the action's own annotation: "The process '/usr/bin/sh' failed
# with exit code 1". Twice that turned a diagnosis into a day of guessing, and
# once into twenty identical messages that shared a single cause nobody could
# see. Annotations written by a workflow command are served to anyone, so in
# "annotate" mode each failure writes one, naming the test and what it said.
# "table" mode writes the same thing as Markdown rows for the run summary page.
#
# A file of its own rather than a string inside shard.sh, because a program
# quoted inside a shell script cannot be run on a sample to see what it does -
# and the first version of this, which was quoted inside shard.sh, had a hole in
# it that could only be found by running it.
#
# awk rather than a parser: this is Gradle's own output, one testcase per line
# with the failure text on the lines after it.

function unescape(s) {
    # XML entities back to characters, or the most common assertion message in
    # this suite reads "expected:&lt;false&gt;". Ampersand last, or an escaped
    # entity would be decoded twice.
    gsub(/&lt;/, "<", s)
    gsub(/&gt;/, ">", s)
    gsub(/&quot;/, "\"", s)
    gsub(/&apos;/, "\047", s)
    gsub(/&amp;/, "\\&", s)
    gsub(/\r/, "", s)
    return s
}

# The text of an element that opens on this line, or the line after it when the
# element's content starts on its own line - which is how Gradle writes both
# failures and system-err.
function body(line, tag) {
    sub(".*<" tag "[^>]*>", "", line)
    gsub(/^[ \t]+|[ \t]+$/, "", line)
    if (line == "") {
        if ((getline line) <= 0) {
            return "(no message)"
        }
        gsub(/^[ \t]+|[ \t]+$/, "", line)
    }
    return line
}

function report(title, message) {
    if (mode == "table") {
        # A bar would start a new cell.
        gsub(/\|/, "\\|", message)
        printf "| `%s` | %s |\n", title, message
    } else {
        # Per cent is what a workflow command escapes with, so it goes last of
        # its own kind; a newline or carriage return would end the command.
        gsub(/%/, "%25", message)
        gsub(/\n/, " ", message)
        printf "::error title=%s::%s\n", title, message
        print title "\t" message > "/dev/stderr"
    }
}

/<testcase / {
    name = ""
    cls = ""
    if (match($0, /name="[^"]*"/))      { name = substr($0, RSTART + 6,  RLENGTH - 7) }
    if (match($0, /classname="[^"]*"/)) { cls  = substr($0, RSTART + 11, RLENGTH - 12) }
    cases++
    open = 1
    next
}

/<(failure|error)[ >]/ {
    if (!open) { next }
    shown = cls
    if (mode == "table") {
        # Only the last part of the package, so the row fits a table.
        n = split(cls, part, ".")
        shown = part[n]
    }
    report(shown "#" name, unescape(body($0, "(failure|error)")))
    open = 0
    next
}

# A run that dies before any test finishes writes a suite with no cases in it
# and the reason here: "Instrumentation run failed due to Process crashed."
# Kept, and reported at the end only if there were no cases at all - which is
# exactly the silence this file exists to end. The first time it happened, the
# annotations said nothing whatsoever.
/<system-err>/ {
    if (crash == "") { crash = body($0, "system-err") }
    next
}

END {
    if (cases == 0 && crash != "") {
        report("the run did not complete", unescape(crash))
    }
}
