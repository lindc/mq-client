import javax.net.ssl.SSLContext;

public class CipherList {
    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("java.vendor  = " + System.getProperty("java.vendor"));
        System.out.println("java.home    = " + System.getProperty("java.home"));
        SSLContext ctx;
        try {
            ctx = SSLContext.getDefault();
        } catch (Throwable t) {
            System.out.println("SSLContext.getDefault() FAILED: " + t);
            return;
        }
        System.out.println("--- supported (" + ctx.getSupportedSSLParameters().getCipherSuites().length + ") ---");
        for (String s : ctx.getSupportedSSLParameters().getCipherSuites()) System.out.println(s);
        System.out.println("--- default enabled (" + ctx.getDefaultSSLParameters().getCipherSuites().length + ") ---");
        for (String s : ctx.getDefaultSSLParameters().getCipherSuites()) System.out.println(s);
        String[] protocols = ctx.getSupportedSSLParameters().getProtocols();
        System.out.println("--- supported protocols ---");
        for (String p : protocols) System.out.println(p);
    }
}
