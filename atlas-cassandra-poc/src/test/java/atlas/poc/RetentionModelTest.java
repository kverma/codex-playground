package atlas.poc;
import java.util.UUID;
class RetentionModelTest extends RetentionContract {
    protected Retention.Store open(UUID subject,RetentionClock clock) { return new Retention.Model(subject,KEY,clock); }
}
