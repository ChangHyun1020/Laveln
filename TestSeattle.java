import java.net.*;
import java.io.*;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.util.regex.*;

public class TestSeattle {
    public static void main(String[] args) throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

        HostnameVerifier allHostsValid = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) { return true; }
        };
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

        // Get session and token
        URL url = new URL("https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus");
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        String cookie = con.getHeaderField("Set-Cookie");
        cookie = cookie != null ? cookie.split(";")[0] : "";

        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuilder content = new StringBuilder();
        while ((inputLine = in.readLine()) != null) content.append(inputLine);
        in.close();

        String token = "";
        String header = "";
        Matcher m1 = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"").matcher(content.toString());
        if(m1.find()) token = m1.group(1);
        Matcher m2 = Pattern.compile("<meta name=\"_csrf_header\" content=\"([^\"]+)\"").matcher(content.toString());
        if(m2.find()) header = m2.group(1);

        // Schedule
        URL schUrl = new URL("https://info.dgtbusan.com/DGT/berth/vesselSchedule");
        HttpsURLConnection schCon = (HttpsURLConnection) schUrl.openConnection();
        schCon.setRequestMethod("POST");
        schCon.setRequestProperty("User-Agent", "Mozilla/5.0");
        schCon.setRequestProperty("Cookie", cookie);
        if(!header.isEmpty()) schCon.setRequestProperty(header, token);
        schCon.setRequestProperty("Content-Type", "application/json");
        schCon.setDoOutput(true);

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd");
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, -5);
        String from = sdf.format(cal.getTime());
        cal.add(java.util.Calendar.DAY_OF_YEAR, 20);
        String to = sdf.format(cal.getTime());

        String payload = "{\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\"}";
        schCon.getOutputStream().write(payload.getBytes("UTF-8"));

        in = new BufferedReader(new InputStreamReader(schCon.getInputStream()));
        StringBuilder schContent = new StringBuilder();
        while ((inputLine = in.readLine()) != null) schContent.append(inputLine);
        in.close();

        String res = schContent.toString();
        // Extract all vessels
        Matcher vM = Pattern.compile("\\{\"vesselCode\":\"([^\"]*)\",\"voyageSeq\":\"([^\"]*)\",\"voyageYear\":\"([^\"]*)\"[^}]+?\"vesselName\":\"([^\"]*)\"").matcher(res);
        while(vM.find()) {
            String vName = vM.group(4);
            System.out.println(vName);
            if(vName.toLowerCase().contains("sea") || vName.toLowerCase().contains("bri")) {
                System.out.println(">>> CHECK THIS: " + vName + ", CODE: " + vM.group(1));
            }
        }
    }
}
