import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

public final class Healthcheck {
    public static void main(String[] args) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:8080/api/ready").toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestMethod("GET");
        try {
            if (connection.getResponseCode() != 200) System.exit(1);
        } catch (IOException failure) {
            System.exit(1);
        } finally {
            connection.disconnect();
        }
    }
}
