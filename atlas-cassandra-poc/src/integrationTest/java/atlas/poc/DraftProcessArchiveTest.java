package atlas.poc;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.DraftProcessClient.*;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static atlas.poc.TraceAssertions.*;

class DraftProcessArchiveTest {
    private int invocation;
    private final List<Map<String,Object>> processes=new ArrayList<>();
    private Result client(DraftProcessServer server,Path journal,String action,int exit,UUID subject,Request template) throws Exception {
        int n=++invocation;Path folder=journal.getParent(),result=folder.resolve("client-"+n+"-result.json"),log=folder.resolve("client-"+n+".log");
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
            Objects.requireNonNull(System.getProperty("atlas.test.classpath")),DraftProcessClient.class.getName(),action,
            Integer.toString(server.port()),journal.toString(),result.toString()));
        if(template!=null) { Path input=folder.resolve("client-"+n+"-template.json");save(input,template);command.add(subject.toString());command.add(input.toString()); }
        Process p=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if(!p.waitFor(45,TimeUnit.SECONDS))fail("client process deadline");
            processes.add(Map.of("pid",p.pid(),"action",action,"exit",p.exitValue(),"result",result.toString(),"journal",journal.toString()));
            assertEquals(exit,p.exitValue(),Files.readString(log));Result actual=JSON.readValue(result.toFile(),Result.class);
            assertEquals(p.pid(),actual.pid());assertNotEquals(ProcessHandle.current().pid(),actual.pid());return actual;
        } finally { if(p.isAlive())p.destroyForcibly(); }
    }
    private Result client(DraftProcessServer s,Path journal,String action,int exit) throws Exception { return client(s,journal,action,exit,null,null); }
    private Journal journal(Path path) throws Exception { return JSON.readValue(path.toFile(),Journal.class); }
    private void prepared(DraftProcessServer s,Path path,UUID subject,Request request) throws Exception {
        assertEquals("OK",client(s,path,"prepare",0,subject,request).reply().code());
        assertEquals(request,journal(path).draft().request());assertNull(journal(path).issued());
    }
    private void sameDependencies(Draft draft,Issued issued) {
        Request a=draft.request(),b=issued.request();
        assertEquals(a.reads(),b.reads());assertEquals(a.updates(),b.updates());assertEquals(a.epoch(),b.epoch());
        assertEquals(a.admittedEpoch(),b.admittedEpoch());assertEquals(a.admittedBudget(),b.admittedBudget());
        assertEquals(b.operation(),issued.ticket().operation());assertEquals(b.hash(),issued.ticket().requestHash());
    }
    private void audit(DraftProcessServer server,ArchiveCassandraContract.Scenario h,Path folder) throws Exception {
        server.observe();var result=new RetentionChecker(h.subject,RetentionContract.KEY).check(server.initial,List.copyOf(server.calls));
        assertEquals(HistoryChecker.Verdict.LINEARIZABLE,result.verdict());
        var evidence=new RetentionChecker.Evidence(0,h.subject,server.initial,List.copyOf(server.calls),result);
        save(folder.resolve("signed-history.json"),evidence);
        var replay=JSON.readValue(folder.resolve("signed-history.json").toFile(),RetentionChecker.Evidence.class);
        assertEquals(result.verdict(),new RetentionChecker(h.subject,RetentionContract.KEY).check(replay.initial(),replay.calls()).verdict());
        // Adversarial control: a server returning changed signed request content must be rejected.
        var bad=new ArrayList<>(server.calls);int index=0;
        while(bad.get(index).issued()==null||!bad.get(index).kind().equals("ISSUE"))index++;
        var c=bad.get(index);var original=c.issued().request();
        var changed=new Request(original.operation(),original.epoch(),original.reads(),Map.of(Group.ROYALTY,"9999"),original.admittedEpoch(),original.admittedBudget());
        bad.set(index,new RetentionChecker.Call(c.start(),c.end(),c.now(),c.kind(),c.draft(),new Issued(changed,c.issued().ticket()),null,null,null));
        var rejected=new RetentionChecker(h.subject,RetentionContract.KEY).check(server.initial,bad);
        assertEquals(HistoryChecker.Verdict.NON_LINEARIZABLE,rejected.verdict());
        save(folder.resolve("mutant-changed-issued-content.json"),new RetentionChecker.Evidence(0,h.subject,server.initial,bad,rejected));
        server.audit=false;
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Preserve a saved offer edit across client exits and archive recovery
     * Boundary: Separate JVMs reload the same journal after an allocation or acceptance reply is dropped; later edits, pruning, logical restore and a backward clock follow
     * Expected: Preserve signed identity, original dependencies and exact receipt; fence restore, keep retired IDs closed and permit a fresh edit.
     */
    @org.junit.jupiter.api.DisplayName("AT-078 | Preserve a saved offer edit across client exits and archive recovery")
    // END ATLAS SCENARIO
    @Test void restartedClientsKeepSignedIdentityThroughArchiveRecovery() throws Exception {
        for(String lost:List.of("issue","commit")) {
            processes.clear();invocation=0;
            var contract=new ArchiveCassandraTest();
            try(var h=contract.new Scenario("signed-process-"+lost)) {
                var clock=new RetentionClock();h.ok(Kind.CLOCK,clock.millis());h.named(Kind.SAVE,"old");
                Path folder=Path.of("build/evidence/signed-process",lost);Files.createDirectories(folder);Path path=folder.resolve("journal.json");
                try(var server=new DraftProcessServer(h,clock)) {
                    try {
                        prepared(server,path,h.subject,Transactions.edit(UUID.randomUUID(),h.store.read().hot().head(),Map.of(Group.ROYALTY,"2000")));
                        Draft draft=journal(path).draft();
                        if(lost.equals("issue")) {
                            byte[] before=Files.readAllBytes(path);server.loseNextReply("issue");
                            assertTrue(client(server,path,"issue",75).error().startsWith("TRANSPORT_UNKNOWN"));
                            assertArrayEquals(before,Files.readAllBytes(path));assertEquals(1,h.external.highWater);assertNull(h.store.read().hot().rows().get(1L).receipt());
                        }
                        var issued=client(server,path,"issue",0).reply().issued();assertEquals(issued,journal(path).issued());sameDependencies(draft,issued);
                        if(lost.equals("commit")) {
                            byte[] before=Files.readAllBytes(path);server.loseNextReply("commit");
                            assertTrue(client(server,path,"commit",75).error().startsWith("TRANSPORT_UNKNOWN"));assertArrayEquals(before,Files.readAllBytes(path));
                            assertNotNull(h.store.read().hot().rows().get(1L).receipt());
                        }
                        Receipt original=client(server,path,"commit",0).reply().receipt();assertEquals(original,h.store.read().hot().rows().get(1L).receipt());
                        Path later=folder.resolve("later.json");
                        prepared(server,later,h.subject,Transactions.edit(UUID.randomUUID(),h.store.read().hot().head(),Map.of(Group.ECONOMICS,"400")));
                        client(server,later,"issue",0);client(server,later,"commit",0);var expected=h.store.read().hot().head();
                        assertEquals(original,client(server,path,"commit",0).reply().receipt());assertEquals(expected,h.store.read().hot().head());
                        assertEquals(1,List.copyOf(server.events).stream().filter(e->Boolean.TRUE.equals(e.get("dropped"))).count());
                        var dropped=List.copyOf(server.events).stream().filter(e->Boolean.TRUE.equals(e.get("dropped"))).findFirst().orElseThrow();
                        Message message=(Message)dropped.get("message");Reply reply=(Reply)dropped.get("reply");assertEquals(lost,message.action());assertEquals(h.subject,message.subject());
                        if(lost.equals("issue")) { assertEquals(draft,message.draft());assertEquals(issued,reply.issued()); }
                        else { assertEquals(issued,message.issued());assertEquals(original,reply.receipt()); }
                        audit(server,h,folder);
                        clock.advance(issued.ticket().expiresAt()-clock.millis());h.ok(Kind.CLOCK,clock.millis());h.ok(Kind.SEAL,2);
                        for(long n=1;n<=2;n++) { h.ok(Kind.PUBLISH,n);h.ok(Kind.COPY,n); }
                        h.ok(Kind.CERTIFY,2);h.ok(Kind.PRUNE,2);assertTrue(h.store.read().hot().rows().isEmpty());
                        h.named(Kind.RESTORE,"old");assertEquals("FENCED",client(server,path,"commit",2).reply().code());h.ok(Kind.RECOVER,0);
                        clock.advance(-clock.millis());h.ok(Kind.CLOCK,0);
                        var recovered=h.store.read();assertEquals(expected,recovered.hot().head());assertEquals(2,recovered.hot().floor());
                        assertEquals("REQUEST_TOO_OLD",client(server,path,"issue",2).reply().code());
                        assertEquals("REQUEST_TOO_OLD",client(server,path,"commit",2).reply().code());assertEquals(recovered,h.store.read());
                        Path fresh=folder.resolve("fresh.json");prepared(server,fresh,h.subject,Transactions.edit(UUID.randomUUID(),expected,Map.of(Group.ROYALTY,"3000")));
                        assertEquals(3,client(server,fresh,"issue",0).reply().issued().ticket().sequence());client(server,fresh,"commit",0);
                        assertEquals("3000",h.store.read().hot().head().value(Group.ROYALTY));h.check(ArchiveRecoveryChecker.Verdict.VALID);
                        assertEquals(processes.size(),processes.stream().map(p->p.get("pid")).distinct().count());
                    } finally { save(folder.resolve("server-wire.json"),server.events);save(folder.resolve("processes.json"),processes); }
                }
            }
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Stop restarted clients from changing an old draft silently
     * Boundary: Reload a stale draft after a conflicting edit, change signed payloads or subjects, and load malformed or unsupported journals
     * Expected: Reject conflicts and tampering without refreshing dependencies, reminting IDs or changing accepted terms; invalid local files send no request.
     */
    @org.junit.jupiter.api.DisplayName("AT-079 | Stop restarted clients from changing an old draft silently")
    // END ATLAS SCENARIO
    @Test void restartedClientsCannotRefreshOrTamperWithSavedDrafts() throws Exception {
        var contract=new ArchiveCassandraTest();
        try(var h=contract.new Scenario("signed-process-stale-and-tampered")) {
            var clock=new RetentionClock();h.ok(Kind.CLOCK,clock.millis());Path folder=Path.of("build/evidence/signed-process/stale");Files.createDirectories(folder);
            try(var server=new DraftProcessServer(h,clock)) {
                try {
                    Path stale=folder.resolve("stale.json"),later=folder.resolve("later.json");var initial=h.store.read().hot().head();
                    prepared(server,stale,h.subject,Transactions.edit(UUID.randomUUID(),initial,Map.of(Group.ROYALTY,"2000")));
                    prepared(server,later,h.subject,Transactions.edit(UUID.randomUUID(),initial,Map.of(Group.ROYALTY,"2500")));
                    client(server,later,"issue",0);client(server,later,"commit",0);var current=h.store.read().hot().head();
                    client(server,stale,"issue",0);sameDependencies(journal(stale).draft(),journal(stale).issued());
                    assertEquals("CONFLICT",client(server,stale,"commit",2).reply().code());assertEquals(current,h.store.read().hot().head());
                    Journal valid=journal(stale);Request r=valid.draft().request();
                    Request changed=new Request(r.operation(),r.epoch(),r.reads(),Map.of(Group.ROYALTY,"3000"),r.admittedEpoch(),r.admittedBudget());
                    Path tampered=folder.resolve("tampered.json");
                    save(tampered,new Journal(1,h.subject,new Draft(changed,valid.draft().anchor(),valid.draft().expiresAt(),valid.draft().signature()),null));
                    assertEquals("INVALID_TICKET",client(server,tampered,"issue",2).reply().code());
                    Issued issued=valid.issued();Request ir=issued.request();
                    save(tampered,new Journal(1,h.subject,valid.draft(),new Issued(new Request(ir.operation(),ir.epoch(),ir.reads(),Map.of(Group.ROYALTY,"3000"),ir.admittedEpoch(),ir.admittedBudget()),issued.ticket())));
                    assertEquals("INVALID_TICKET",client(server,tampered,"commit",2).reply().code());
                    audit(server,h,folder);
                    save(tampered,new Journal(1,UUID.randomUUID(),valid.draft(),valid.issued()));
                    assertEquals("INVALID_TICKET",client(server,tampered,"commit",2).reply().code());
                    int requests=server.events.size();Files.writeString(tampered,"{broken");assertTrue(client(server,tampered,"issue",65).error().startsWith("LOCAL_INVALID"));assertEquals(requests,server.events.size());
                    save(tampered,new Journal(99,h.subject,valid.draft(),valid.issued()));assertTrue(client(server,tampered,"commit",65).error().startsWith("LOCAL_INVALID"));assertEquals(requests,server.events.size());
                    assertEquals(current,h.store.read().hot().head());assertEquals(2,h.external.highWater);h.check(ArchiveRecoveryChecker.Verdict.VALID);
                } finally { save(folder.resolve("server-wire.json"),server.events);save(folder.resolve("processes.json"),processes); }
            }
        }
    }
}
