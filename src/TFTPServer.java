import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TFTPServer {
    public static final int TFTPPORT = 4970;
    public static final int BUFSIZE = 516;
    public static final String READDIR = "src/dir/read/"; //custom address at your PC
    public static final String WRITEDIR = "src/dir/write/"; //custom address at your PC
    // OP codes
    public static final int OP_RRQ = 1;
    public static final int OP_WRQ = 2;
    public static final int OP_DAT = 3;
    public static final int OP_ACK = 4;
    public static final int OP_ERR = 5;
    public static final int MAXTRANSMITSIZE = 512;
    public static final int ACKPACKETSIZE = 4;
    private DatagramPacket receivedDataPacket;

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
                        else if (reqtype==OP_WRQ) {
                            requestedFile.insert(0, WRITEDIR);
                            HandleRQ(sendSocket, requestedFile.toString(), OP_WRQ);
                        }
                        else{
                            send_ERR(sendSocket, 4, "Illegal TFTP operation.");
                        }
                        sendSocket.close();
                    } catch (SocketException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
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
        StringBuffer mode = new StringBuffer();
        mode.append(new String(buf, nameFinish + 1, 5));
        System.out.println(mode.toString() + " the mode");
        if (!mode.toString().equals("octet")) {
            System.out.println("mode is not implemented");
            throw new IllegalArgumentException("transfer mode not supported!");
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
    private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) throws IOException {

        if (opcode == OP_RRQ) {
            try {

                // See "TFTP Formats" in TFTP specification for the DATA and ACK packet contents
                File file = new File(requestedFile);
                if (!file.exists()) {
                    send_ERR(sendSocket, 1, "File not found");
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
            try {
                File file = new File(requestedFile);
                // Check if file exists
                if (!file.exists()) {
                    FileOutputStream fos = new FileOutputStream(file);
                    int retransmitCnt = 0;
                    boolean fin = false;
                    int i = 0;
                    // send an initiate ACK to star the communication
                    sendACK(sendSocket, i);


                    while (!fin) {

                        boolean result = true;
                        // start sending the data in packet of size 512
                        result = receive_DATA_send_ACK(sendSocket, i + 1);
                        //add packets content without the 4 first bytes to the file
                        fos.write(receivedDataPacket.getData(), 4, receivedDataPacket.getLength() - 4);

                        //last packet to send
                        if (receivedDataPacket.getLength() < MAXTRANSMITSIZE + 4) {
                            fin = true;
                            System.out.println("\nFile with size " + file.length() + " has been transmitted");
                            fos.close();
                        } else
                            System.out.println("\nTransmission number " + i + " has been sent");
                        retransmitCnt = 0;

                        if (!result) {
                            // if the data is not sent successfully
                            if (retransmitCnt == 5) {
                                // MAX allowed retransmissions is 5
                                send_ERR(sendSocket, 0, "Terminated. Max allowed re-transmission is 5");
                                break;
                            }
                            i--;
                            retransmitCnt++;
                        }
                        i++;

                    }
                } else {
                    // else the file is already exist, we throw error number 6
                    send_ERR(sendSocket, 6, "File already exists");
                    return;
                }

                File dir = new File(WRITEDIR);
                long filesSize = 0;
                long maxFolderSize = 100000000;   // set the maximum size the folder can have
                for (File files : dir.listFiles()) {
                    filesSize += files.length();   // calculate the size of the exist folders
                }
                if (maxFolderSize - filesSize < receivedDataPacket.getLength()) {
                    System.out.println("Disk is full");
                    send_ERR(sendSocket, 3, "Disk full or allocation exceeded.");
                    return;
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                send_ERR(sendSocket,2,"Access violation");
            }

        }

    }

    /**
     * This method is to be used in Write request while sending an initiate ACk
     *
     * @param sendSocket
     * @param i          the packet number
     * @return true when the process is completed
     * @throws IOException
     */
    public boolean sendACK(DatagramSocket sendSocket, int i) throws IOException {
        short block = (short) i;
        ByteBuffer packet = ByteBuffer.allocate(4);
        packet.putShort((short) OP_ACK);
        packet.putShort(block);
        sendSocket.send(new DatagramPacket(packet.array(), packet.position()));
        System.out.println("ACK is sent: Block number is " + block);
        return true;
    }

    private boolean send_DATA_receive_ACK(DatagramSocket sendSocket, int i, byte[] bytes) throws IOException {
        //       DATA packet
        //|  2bytes   |	2bytes |  n bytes |
        //|----------|---------|----------|
        //|  OpCode  |  Block# |   Data   |
        try {

            short block = (short) i;

            ByteBuffer packet = ByteBuffer.allocate(bytes.length + 4);  // the size is the data size + 4 bytes fir block and opcode

            //  create the packet by putting the data
            packet.putShort((short) OP_DAT);
            packet.putShort(block);
            packet.put(bytes);
            // Send the packet
            sendSocket.send(new DatagramPacket(packet.array(), packet.position()));
            System.out.println("Data is sent");
            // create a new array to receive  the ACK
            byte[] recACK = new byte[ACKPACKETSIZE];  // ack packet is 4 bytes length

            DatagramPacket receivedACKPacket = new DatagramPacket(recACK, ACKPACKETSIZE);

            sendSocket.setSoTimeout(150);  // a time out or RTT which is the time we wait for the ACK
            sendSocket.receive(receivedACKPacket);
            System.out.println("ACK is received");
            // wrap the received ack packet to get its information
            ByteBuffer wrap = ByteBuffer.wrap(recACK);
            short opCode = wrap.getShort();
            short blockNr = wrap.getShort();
            if (opCode != OP_ACK && opCode!=OP_ERR) {  // check if the received packet is ACK packet
                send_ERR(sendSocket, 4, "Illegal TFTP operation.");
            } else if (blockNr != block) {  // check if the received packet belongs to the right data sent
                return false;
            }
        } catch (SocketTimeoutException e ){
            System.out.println("Time out");
        } catch (IOException e) { // when unknown error happens
            send_ERR(sendSocket, 2, "Access violation.");
            System.out.println("IO problem that might be an Access violation");

        }

        return true;
    }


    private boolean receive_DATA_send_ACK(DatagramSocket sendSocket, int i) throws IOException {
        //       ACK packet
        //| 2bytes   |	2bytes |
        //|----------|---------|
        //|  OpCode  |  Block# |
        try {
            byte[] data = new byte[BUFSIZE];
            receivedDataPacket = new DatagramPacket(data, BUFSIZE);
            sendSocket.setSoTimeout(150);
            sendSocket.receive(receivedDataPacket);
            System.out.print("packet received: Size " + receivedDataPacket.getLength() + " Number: ");


            byte[] recPacketInformation = receivedDataPacket.getData();
            // wrap the received information to get
            ByteBuffer wrap = ByteBuffer.wrap(recPacketInformation);
            int opCode = wrap.getShort();
            int blockNr = wrap.getShort();  // get the packet number
            System.out.println(blockNr);
            //if the received packet is not a data packet or error packet throw Illegal TFTP operation
            if (opCode != OP_DAT && opCode != OP_ERR) {
                send_ERR(sendSocket, 4, "Illegal TFTP operation.");
                throw new SocketTimeoutException();
            }
            // when the client use different port while sending the packet
            if (receivedDataPacket.getPort() != sendSocket.getPort()) {
                // disconnect first and create a new connection with new port to send the message error
                sendSocket.disconnect();
                sendSocket.connect(new InetSocketAddress(sendSocket.getInetAddress(), receivedDataPacket.getPort()));
                send_ERR(sendSocket, 5, "Unknown transfer ID");
            }
            // check blockNr of the sennt ACK is not the same of the packet ID
            else if (blockNr != i) {
                send_ERR(sendSocket, 5, "Unknown transfer ID");
                System.out.println("Data packet number and ACK block number does not match");
                throw new SocketException();
            } else {
                // send ACK when the data packet is received
                sendACK(sendSocket, blockNr);

            }
        }catch (SocketTimeoutException e) {
            System.out.println("Time out");
        } catch
         (IOException e) { // when unknown error happens
            send_ERR(sendSocket, 2, "Access violation.");
        }
        return true;
    }

    private void send_ERR(DatagramSocket sendSocket, int errorNumber, String errorMsg) throws IOException {
        //          error packet
        // |2bytes |   2bytes   |   n bytes  |   1byte |
        // |-------|------------|------------|---------|
        // |   05  |  ErrorCode |   ErrMsg   |     0   |

        byte[] buf = errorMsg.getBytes();   // add the error message to a buffer
        ByteBuffer packet = ByteBuffer.allocate(errorMsg.getBytes().length + 4);  // message length + 4 (2 bytes opCode adn 2 bytes ErrorCode)
        // put the opcode then error number then the message in the bytebuffer to send as a datagram packet
        packet.putShort((short) OP_ERR);
        packet.putShort((short) errorNumber);
        packet.put(buf);
        sendSocket.send(new DatagramPacket(packet.array(), packet.position()));

    }

}
