/* Intentionally flawed publication protocol: stale preflight authorizes a later overwrite. */
byte published = 0;
byte maxCommitted = 0;
proctype Publisher(byte revision) {
    bool approved = false;
    atomic {
        if
        :: revision > published ->
             approved = true;
             printf("TRACE|preflight|%d\n", revision)
        :: else ->
             printf("TRACE|reject|%d\n", revision)
        fi
    };
    if
    :: approved ->
         atomic {
             published = revision;
             if
             :: revision > maxCommitted -> maxCommitted = revision
             :: else -> skip
             fi;
             printf("TRACE|commit|%d\n", revision);
             assert(published == maxCommitted)
         }
    :: else -> skip
    fi
}
init {
    atomic {
        run Publisher(1);
        run Publisher(2)
    }
}
