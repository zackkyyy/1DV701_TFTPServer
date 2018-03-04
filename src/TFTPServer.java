import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TFTPServer {
    public static final int TFTPPORT = 4970;
    public static final int BUFSIZE = 516;
    public static final String READDIR = "/Users/zack/Downloads/TFTPServer/src/dir/read/"; //custom address at your PC
    public static final String WRITEDIR = "/Users/zack/Downloads/TFTPServer/src/dir/write/"; //custom address at your PC
    // OP codes
    public static final int OP_RRQ = 1;
    public static final int OP_WRQ = 2;
    public static final int OP_DAT = 3;
    public static final int OP_ACK = 4;
    public static final int OP_ERR = 5;
    public static final int MAXTRANSMITSIZE = 512;
    public static final int ACKPACKETSIZE = 4;

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
                                requestedFile, clientAddress.getHostName(), clientAddress.getPort());

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

        if (opcode == OP_RRQ) {
            try {

                // See "TFTP Formats" in TFTP specification for the DATA and ACK packet contents
                File file = new File(requestedFile);
                if (!file.exists()) {
                    System.out.println("File cannot be found");
                    return;
                }
                FileInputStream fileIS;
                fileIS = new FileInputStream(file);
                byte[] buffer = new byte[(int) file.length()];
                fileIS.read(buffer);
                int transmissionNr = (int) ((file.length() / MAXTRANSMITSIZE) + 1);   // max size of each transmission is 512
                int retransmitCnt = 0;
                for (int i = 0; i < transmissionNr; i++) {
                    boolean result = false;
                    // Not the last packet
                    if (i + 1 < transmissionNr) {
                        System.out.println("Transmission number " + i + " has been sent");
                        result = send_DATA_receive_ACK(sendSocket, i + 1, Arrays.copyOfRange(buffer, i * MAXTRANSMITSIZE, (i + 1) * MAXTRANSMITSIZE));
                        retransmitCnt = 0;
                    } else {
                        System.out.println("File with size " + file.length() + " has been transmitted");
                        result = send_DATA_receive_ACK(sendSocket, i + 1, Arrays.copyOfRange(buffer, i * MAXTRANSMITSIZE, (int) file.length()));
                        retransmitCnt = 0;
                    }
                    if (!result) {
                        // if the data is not sent successfully
                        if (retransmitCnt == 5) {
                            send_ERR(sendSocket, 0, "Terminated. Max allowed re-transmission is 5");
                            break;
                        }
                        i--;
                        retransmitCnt++;
                    }
                }
                fileIS.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();

            }
        } else if (opcode == OP_WRQ) {

            //boolean result = receive_DATA_send_ACK(params);
        } else {
            System.err.println("Invalid request. Sending an error packet.");
            // See "TFTP Formats" in TFTP specification for the ERROR packet contents
            //	send_ERR(params);
            return;
        }

    }


    // To be implemented
    private boolean send_DATA_receive_ACK(DatagramSocket sendSocket, int i, byte[] bytes) throws IOException {
        //       DATA packet
        //|  2bytes   |	2bytes |  n bytes |
        //|----------|---------|----------|
        //|  OpCode  |  Block# |   Data   |

        short block = (short) i;

        ByteBuffer packet = ByteBuffer.allocate(bytes.length + 4);

        packet.putShort((short) OP_DAT);
        packet.putShort(block);
        packet.put(bytes);

        sendSocket.send(new DatagramPacket(packet.array(), packet.position()));
        System.out.println("Data is sent");
        byte[] recACK = new byte[ACKPACKETSIZE];  // ack packet is 4 bytes length
        DatagramPacket receivedACKPacket = new DatagramPacket(recACK, ACKPACKETSIZE);
        sendSocket.setSoTimeout(150);
        sendSocket.receive(receivedACKPacket);
        System.out.println("ACK is received");

        ByteBuffer wrap = ByteBuffer.wrap(recACK);
        short opCode = wrap.getShort();
        short blockNr = wrap.getShort();
        if (opCode != OP_ACK) {  // check if the received packet is ACK packet
            send_ERR(sendSocket, 5, "Unknown transfer ID");
        } else if (blockNr != block) {  // check if the received packet belongs to the right data sent
            return false;
        }

        return true;
    }

    /*
    private boolean receive_DATA_send_ACK(params) {
   //       ACK packet
   //| 2bytes   |	2bytes |
   //|----------|---------|
   //|  OpCode  |  Block# |
   return true;
   }*/
    private void send_ERR(DatagramSocket sendSocket, int errorNumber, String errorMsg) throws IOException {
        //          error packet
        // |2bytes |   2bytes   |   n bytes  |   1byte |
        // |-------|------------|------------|---------|
        // |   05  |  ErrorCode |   ErrMsg   |     0   |

        byte[] buf = errorMsg.getBytes();

        ByteBuffer packet = ByteBuffer.allocate(errorMsg.getBytes().length + 5);
        packet.putShort((short) OP_ERR);
        packet.putShort((short) errorNumber);
        packet.put(buf);
        sendSocket.send(new DatagramPacket(packet.array(), packet.position()));

    }

}
