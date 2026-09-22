import java.nio.file.Files;
import java.nio.file.Path;

public class Extract {
    public static void main(String[] a) throws Exception {
        String xml = Files.readString(Path.of(
            "ibm-mq-service/build/test-results/jvmTest/TEST-com.mqclient.ibmmq.diag.IbmMQSslDiagTest.xml"));
        int s = xml.indexOf("<system-out><![CDATA[");
        int e = xml.indexOf("]]></system-out>");
        System.out.println(s < 0 ? "NO system-out found" : xml.substring(s + 20, e));
    }
}
