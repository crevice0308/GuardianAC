package test.guardian;
import dev.guardianac.storage.SqliteBanRepository;
import java.nio.file.Path;
public class OfflineRecovery {public static void main(String[] args){Path quarantine=SqliteBanRepository.restoreBackup(Path.of(args[0]),args[1]);System.out.println("GACTEST RECOVERED "+quarantine.getFileName());}}
