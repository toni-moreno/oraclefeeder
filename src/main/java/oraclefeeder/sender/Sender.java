package oraclefeeder.sender;

/**
 * Created by Alberto Pascual on 22/06/15.
 */
public interface Sender {

    public boolean isConnected();

    public void send(String host, String metrica);
}
