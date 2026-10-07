package atlas.poc;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.DraftProcessClient.*;
import static atlas.poc.DurableArchiveServer.*;
import static atlas.poc.TraceAssertions.*;

@Tag("fault")
class RootAuthorityFaultTest {
    private FrameProxy.Witness witness(FrameProxy proxy,UUID subject,String operation,FrameProxy.Fault fault) throws Exception {
        assertTrue(proxy.injected.await(1,TimeUnit.SECONDS),"exact root query must be intercepted");
        var w=proxy.witness();assertNotNull(w);assertEquals(1,proxy.matchingRequests.get(),"no transparent target retry or bypass");
        assertTrue(w.query().contains(CassandraRootAuthority.marker(subject,operation)));assertEquals(fault,w.fault());
        if(fault==FrameProxy.Fault.BEFORE_SEND) { assertFalse(w.forwarded());assertEquals(-1,w.responseOpcode()); }
        else { assertTrue(w.forwarded());assertEquals(8,w.responseOpcode(),"RESULT not ERROR"); }
        return w;
    }
    private String shortString(DataInputStream in) throws Exception { return new String(in.readNBytes(in.readUnsignedShort()),java.nio.charset.StandardCharsets.UTF_8); }
    private void applied(FrameProxy.Witness w) throws Exception {
        try(var in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(w.responseBody())))) {
            assertEquals(2,in.readInt());int flags=in.readInt();assertEquals(0,flags&~1);assertEquals(1,in.readInt());
            if((flags&1)!=0) { shortString(in);shortString(in); }
            if((flags&1)==0) { shortString(in);shortString(in); }
            assertEquals("[applied]",shortString(in));assertEquals(4,in.readUnsignedShort());
            assertEquals(1,in.readInt());assertEquals(1,in.readInt());assertEquals(1,in.readUnsignedByte());assertEquals(0,in.available());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep a cached recovery pointer from authorizing edits during an authority outage
     * Boundary: Drop an exact authority SELECT before forwarding and drop a real SELECT reply while a valid older local cache exists
     * Expected: Return an explicit unknown outcome with no offer, cache or root change; a fresh process recovers the exact original receipt when reads work again.
     */
    @org.junit.jupiter.api.DisplayName("AT-087 | Keep a cached recovery pointer from authorizing edits during an authority outage")
    // END ATLAS SCENARIO
    @Test void lostAuthorityReadsNeverFallBackToStaleCache() throws Exception {
        try(var h=new DurableArchiveProcessTest().new Harness("wire-read","authority")) {
            byte[] oldCache=Files.readAllBytes(h.state);
            var draft=h.run(h.message("prepare",null,null,null),"OK").reply().draft();
            var issued=h.run(h.message("issue",draft,null,null),"OK").reply().issued();
            var receipt=h.run(h.message("commit",null,issued,null),"OK").reply().receipt();
            for(var fault:List.of(FrameProxy.Fault.BEFORE_SEND,FrameProxy.Fault.AFTER_RESPONSE)) {
                Files.write(h.state,oldCache);var before=h.actual();
                CassandraRootAuthority.Version root;
                try(var a=new CassandraRootAuthority(h.subject)) { root=a.read(); }
                FrameProxy.Witness w;
                try(var proxy=new FrameProxy()) {
                    h.authorityPort=proxy.port();proxy.armQuery(fault,CassandraRootAuthority.marker(h.subject,"READ"));
                    Path report=h.process(h.message("commit",null,issued,null),75);
                    var error=JSON.readTree(report.toFile());assertEquals("AUTHORITY_UNKNOWN",error.get("phase").asText());
                    assertTrue(error.get("error").asText().contains("DriverTimeoutException"),error.toString());
                    w=witness(proxy,h.subject,"READ",fault);
                } finally { h.authorityPort=9042; }
                assertEquals(before,h.actual());assertArrayEquals(oldCache,Files.readAllBytes(h.state));
                try(var a=new CassandraRootAuthority(h.subject)) { assertEquals(root,a.read()); }
                save(h.folder.resolve("read-"+fault+".json"),Map.of("witness",w,"before",before,"after",h.actual(),"authority",root,"matchingRequests",1));
                assertEquals(receipt,h.run(h.message("commit",null,issued,null),"OK").reply().receipt());
            }
            h.check();
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover a root update whose request or reply was lost
     * Boundary: Persist the proposed guard, drop the exact conditional write before send or after an applied reply, then observe a later root with identical content
     * Expected: Fresh reads distinguish the exact published proposal from unchanged authority; later-writer ambiguity stays unknown and stale retries cannot overwrite it.
     */
    @org.junit.jupiter.api.DisplayName("AT-088 | Recover a root update whose request or reply was lost")
    // END ATLAS SCENARIO
    @Test void uncertainRootPublicationRecoversExactProposalWithoutGuessing() throws Exception {
        for(var fault:List.of(FrameProxy.Fault.BEFORE_SEND,FrameProxy.Fault.AFTER_RESPONSE))
            try(var h=new DurableArchiveProcessTest().new Harness("wire-publish-"+fault,"authority")) {
                var draft=h.run(h.message("prepare",null,null,null),"OK").reply().draft();
                byte[] beforeCache=Files.readAllBytes(h.state);var beforeHot=h.actual();FrameProxy.Witness w;Report uncertain;
                try(var proxy=new FrameProxy()) {
                    h.authorityPort=proxy.port();proxy.armQuery(fault,CassandraRootAuthority.marker(h.subject,"CAS"));
                    uncertain=JSON.readValue(h.process(h.message("issue",draft,null,null),75).toFile(),Report.class);
                    assertEquals("ROOT_PUBLICATION_UNKNOWN",uncertain.phase());assertNull(uncertain.reply());
                    assertTrue(JSON.readTree(h.state.resolveSibling("publication-error.json").toFile()).get("error").asText().contains("DriverTimeoutException"));
                    w=witness(proxy,h.subject,"CAS",fault);if(fault==FrameProxy.Fault.AFTER_RESPONSE)applied(w);
                } finally { h.authorityPort=9042; }
                var proposal=JSON.readValue(h.state.resolveSibling("root-proposal.json").toFile(),CassandraRootAuthority.Proposal.class);
                save(h.folder.resolve("wire-proposal.json"),proposal);
                assertEquals(beforeHot,h.actual(),"binding publication cannot install or accept the edit yet");assertArrayEquals(beforeCache,Files.readAllBytes(h.state));
                boolean published=fault==FrameProxy.Fault.AFTER_RESPONSE;
                try(var a=new CassandraRootAuthority(h.subject)) {
                    var observed=a.read();assertEquals(published?proposal.next():proposal.expected(),observed);
                    var resolution=a.resolve(proposal);
                    assertEquals(published?CassandraRootAuthority.Resolution.PUBLISHED:CassandraRootAuthority.Resolution.UNCHANGED,resolution);
                    save(h.folder.resolve("wire-resolution.json"),Map.of("witness",w,"proposal",proposal,"observed",observed,"resolution",resolution,"matchingRequests",1));
                    if(published)h.frames.addAll(uncertain.frames()); // Only the actually published reservation is durable.
                }
                var issued=h.run(h.message("issue",draft,null,null),"OK").reply().issued();
                assertEquals(uncertain.facts().bindings().values().iterator().next(),issued);
                var receipt=h.run(h.message("commit",null,issued,null),"OK").reply().receipt();
                assertEquals(receipt,h.run(h.message("commit",null,issued,null),"OK").reply().receipt());h.check();
                // A later root can have identical content but a different guard. Payload equality cannot resolve provenance.
                try(var a=new CassandraRootAuthority(h.subject)) {
                    var later=a.read();assertTrue(a.compareAndSet(later,proposal.next().root()));var superseded=a.read();
                    assertEquals(proposal.next().root(),superseded.root());assertNotEquals(proposal.next().guard(),superseded.guard());
                    assertEquals(CassandraRootAuthority.Resolution.UNKNOWN,a.resolve(proposal));
                    assertFalse(a.publish(proposal));assertEquals(superseded,a.read(),"stale proposal retry cannot overwrite a later writer");
                    save(h.folder.resolve("superseded.json"),Map.of("proposal",proposal,"later",superseded,"resolution",a.resolve(proposal),"afterRetry",a.read()));
                }
            }
    }
}
