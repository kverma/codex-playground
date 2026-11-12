/* Fixed: commit-time atomic guard ensures monotonic published revision. */
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
             if
             :: revision > published ->
                  published = revision;
                  maxCommitted = revision;
                  printf("TRACE|commit|%d\n", revision)
             :: else ->
                  printf("TRACE|skip|%d\n", revision)
             fi;
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
