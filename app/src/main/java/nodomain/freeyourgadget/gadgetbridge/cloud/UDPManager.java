package nodomain.freeyourgadget.gadgetbridge.cloud;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.io.IOException;

public class UDPManager {
    private static final int PORT = 1883;  // You can use your desired port
    private DatagramSocket socket;
    private InetAddress address;

    public UDPManager() {
        try {
            socket = new DatagramSocket();
            address = InetAddress.getByName("192.168.0.174");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Send UDP datagram
    public void sendMessage(String message) {
        try {
            byte[] sendData = message.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, PORT);
            socket.send(sendPacket);
            System.out.println("Message sent: " + message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Receive UDP datagram
    public void receiveMessage() {
        try {
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            socket.receive(receivePacket);
            String message = new String(receivePacket.getData(), 0, receivePacket.getLength());
            System.out.println("Message received: " + message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Close the socket
    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
            System.out.println("Socket closed.");
        }
    }

    public static void main(String[] args) {
        UDPManager udpManager = new UDPManager();

        // Send a sample message
        udpManager.sendMessage("Hello from UDP!");

        // To receive a message (if there is any)
        udpManager.receiveMessage();

        // Close socket after operations
        udpManager.close();
    }
}
