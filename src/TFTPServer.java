import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;

public class TFTPServer {
    public static final int TFTPPORT = 4970;
    public static final int BUFSIZE = 516;
    public static final String READDIR = "/src/dir/read"; //custom address at your PC
    public static final String WRITEDIR = "/src/dir/write"; //custom address at your PC
    // OP codes
    public static final int OP_RRQ = 1;
    public static final int OP_WRQ = 2;
    public static final int OP_DAT = 3;
    public static final int OP_ACK = 4;
    public static final int OP_ERR = 5;

    public static void main(String[] args) {
        if (args.length > 0) {
            System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
            System.exit(1);
        }
        //Starting the server
        try {
            TFTPServer server = new TFTPServer();
            server.start();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    private void start() throws SocketException {

        byte[] buf = new byte[BUFSIZE];

        // Create socket
        DatagramSocket socket = new DatagramSocket(null);

        // Create local bind point
        SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
        socket.bind(localBindPoint);
        System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

        // Loop to handle client requests
        while (true) {

            final InetSocketAddress clientAddress = receiveFrom(socket, buf);

            // If clientAddress is null, an error occurred in receiveFrom()
            if (clientAddress == null)
                continue;

            final StringBuffer requestedFile = new StringBuffer();
            final int reqtype = ParseRQ(buf, requestedFile);

            new Thread() {
                public void run() {
                    try {
                        DatagramSocket sendSocket = new DatagramSocket(0);
                        // Connect to client
                        sendSocket.connect(clientAddress);

                        System.out.printf("%s request for %s from %s using port %d\n",
                                (reqtype == OP_RRQ) ? "Read" : "Write",
                                clientAddress.getHostName(), clientAddress.getPort());

                        // Read request
                        if (reqtype == OP_RRQ) {
                            requestedFile.insert(0, READDIR);
                            HandleRQ(sendSocket, requestedFile.toString(), OP_RRQ);
                        }
                        // Write request
                        else {
                            requestedFile.insert(0, WRITEDIR);
                            HandleRQ(sendSocket, requestedFile.toString(), OP_WRQ);
                        }
                        sendSocket.close();
                    } catch (SocketException e) {
                        e.printStackTrace();
                    }
                }
            }.start();
        }
    }

    /**
     * Reads the first block of data, i.e., the request for an action (dir.read or dir.write).
     *
     * @param socket (socket to dir.read from)
     * @param buf    (where to store the dir.read data)
     * @return socketAddress (the socket address of the client)
     */
    private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) {

        DatagramPacket receivedPack = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(receivedPack);
        } catch (IOException e) {
            e.printStackTrace();
        }

        InetSocketAddress socketAddress = new InetSocketAddress(receivedPack.getAddress(), receivedPack.getPort());
        return socketAddress;
    }

    /**
     * Parses the request in buf to retrieve the type of request and requestedFile
     *
     * @param buf           (received request)
     * @param requestedFile (name of file to dir.read/dir.write)
     * @return opcode (request type: RRQ or WRQ)
     */
    private int ParseRQ(byte[] buf, StringBuffer requestedFile) {
        // See "TFTP Formats" in TFTP specification for the RRQ/WRQ request contents
        ByteBuffer buffer = ByteBuffer.wrap(buf);

        int nameFinish = 0;

        for (int i = 1; i < buf.length; i++) {
            /*
            in the read write request packet |opcode|filename|0| so this loop stops
            at the |0| to get the file name
             */
            if (buf[i] == 0) {
                nameFinish = i;
                break;   // break the loop as we got the index where the file name end
            }
        }
        int opcode = buffer.getShort();
        System.out.println("The opcode is " + opcode);
        requestedFile.append(new String(buf, 2, nameFinish - 2));
        System.out.println("The requested file is " + requestedFile);
        return opcode;
    }

    /**
     * Handles RRQ and WRQ requests
     *
     * @param sendSocket    (socket used to send/receive packets)
     * @param requestedFile (name of file to dir.read/dir.write)
     * @param opcode        (RRQ or WRQ)
     */
    private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) {
        /*
        if(opcode == OP_RRQ)
        {
            // See "TFTP Formats" in TFTP specification for the DATA and ACK packet contents


            //boolean result = send_DATA_receive_ACK(params);
        }
        else if (opcode == OP_WRQ)
        {
            //boolean result = receive_DATA_send_ACK(params);
        }
        else
        {
            System.err.println("Invalid request. Sending an error packet.");
            // See "TFTP Formats" in TFTP specification for the ERROR packet contents
            //	send_ERR(params);
            return;
        }
        */
    }

    /**
     To be implemented
     private boolean send_DATA_receive_ACK(params) {
     //       DATA packet
     //| 2bytes   |	2bytes |  n bytes |
     //|----------|---------|----------|
     //|  OpCode  |  Block# |   Data   |
     return true;
     }
     private boolean receive_DATA_send_ACK(params) {
     //       ACK packet
     //| 2bytes   |	2bytes |
     //|----------|---------|
     //|  OpCode  |  Block# |
     return true;
     }
     private void send_ERR(params) {
     //          error packet
     // |2bytes |   2bytes   |   n bytes  |   1byte |
     // |-------|------------|------------|---------|
     // |   05  |  ErrorCode |   ErrMsg   |     0   |
     }
     */
}
