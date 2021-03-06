package net.osmand.bitcoinsender;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.stream.JsonReader;

import net.osmand.bitcoinsender.model.AccountBalance;
import net.osmand.bitcoinsender.model.Withdrawal;
import net.osmand.bitcoinsender.utils.BlockIOException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * Created by Paul on 07.06.17.
 */
public class CoinSenderMain {

    private static String guid;
    private static String pass;
    private static String directory;
	private static int PART_SIZE = 80;
	private static double MIN_PAY = 0.0001;

    public static void main(String args[]) throws IOException {

        if (args.length <= 0) {
            System.out.println("Usage: --prepare | --send");
            return;
        }
        Scanner in = new Scanner(System.in);
        System.out.print("Enter your API key: ");
        guid = in.nextLine();
        System.out.print("Enter your PIN: ");
        pass = in.nextLine();
        System.out.print("Enter full path to JSON file: ");
        directory = in.nextLine();
        System.out.print("Enter your part size (default 80): ");
        String ll = in.nextLine();
        if(ll.trim().length() > 0) {
        	PART_SIZE = Integer.parseInt(ll);
        }
        System.out.print("Enter minimal amount to pay (default 0.0001): ");
        ll = in.nextLine();
        if(ll.trim().length() > 0) {
        	MIN_PAY = Double.parseDouble(ll);
        }

        if (guid.equals("") || pass.equals("")) {
            System.out.println("You forgot to enter Client_ID or Secret. Exiting...");
            return;
        }

        BlockIO api = new BlockIO(guid);
        Map<String, Object> recipients = new LinkedHashMap<String, Object>();

        System.out.println();

        AccountBalance balance = null;
        try {
            balance = api.getAccountBalance();
        } catch (BlockIOException e) {
            e.printStackTrace();
            System.out.println("Incorrect API key. Exiting...");
            return;
        }
        System.out.println("Balance for account " + balance.network
                + ": Confirmed: " + balance.availableBalance
                + " Pending: " + balance.getPendingReceivedBalance());
        System.out.println();


        Gson gson = new Gson();
        File file = new File(directory);
        if (!file.exists()) {
            while (!file.exists()) {
                System.out.print("You have entered incorrect file path. Please try again: ");
                directory = in.nextLine();
                file = new File(directory);
            }
        }
        JsonReader reader = new JsonReader(new FileReader(directory));
        recipients.putAll((Map) gson.fromJson(reader, Map.class));
        List<LinkedTreeMap> paymentsList = (ArrayList) recipients.get("payments");
        Map<String, Double> payments = new LinkedHashMap<String, Double>();
        double allMoney = 0;
        for (LinkedTreeMap map : paymentsList) {
            Double btc = (Double) map.get("btc");
            String address = (String) map.get("btcaddress");
            allMoney += btc;
            if (payments.containsKey(address)) {
                payments.put(address, btc + payments.get(address));
            } else {
            	payments.put(address, btc);
            }
        }
        List<Map> splitPayment = splitResults(payments);
        for (Map<String, Double> map : splitPayment) {
            payments.putAll(map);
        }

        if (args.length > 0) {
            if (args[0].equals("--prepare")) {
                String totalSum = String.format("%.12f", (allMoney));
                System.out.println("Number of chunks: " + splitPayment.size());
                System.out.println("Total sum in BTC: " + totalSum);
            }
            else if (args[0].equals("--send")) {
                boolean done = false;
                List<Integer> paidChunks = new ArrayList<Integer>();
                while (!done) {
                    System.out.println("Number of chunks: " + splitPayment.size());
                    System.out.println("You have already paid for these chunks: " + paidChunks.toString());
                    String chunkString = "Chunks: ";
                    for(int i = 0; i < splitPayment.size(); i++) {
                    	chunkString += (i + 1) + ". " + (i * PART_SIZE + 1) + "-" + ((i + 1) * PART_SIZE ) + ", ";
                    }
                    System.out.println(chunkString);
                    System.out.print("Enter the number of chunk you want to pay for ('-1' to exit): ");
                    int chunk = in.nextInt() - 1;
                    if (chunk == -2) {
                        break;
                    }
                    while (chunk < 0 || chunk >= splitPayment.size()) {
                        System.out.print("Please enter a number between 1 and " + splitPayment.size() + ": ");
                        chunk = in.nextInt() - 1;
                        if (chunk == -2) {
                            break;
                        }
                    }
                    if (paidChunks.contains(chunk + 1)) {
                        System.out.println("You've already paid for this chunk!");
                        continue;
                    }
                    Map<String, Double> paymentToPay = splitPayment.get(chunk);
                    Map<String, Double> currentPayment = new LinkedHashMap<>();
                    System.out.println("All payments: ");
                    double total = 0l;
                    int id = 0;
                    for (String key : paymentToPay.keySet()) {
                    	id++;
                    	Double toPay = paymentToPay.get(key);
                        String currentPaymentString = String.format("%.12f", toPay);
                        int addId = chunk * PART_SIZE + id;
                        if(toPay >= MIN_PAY) {
                        	total += toPay;
                        	currentPayment.put(key, toPay);
                        } else {
                        	currentPaymentString += " [NOT PAID - less than minimal] ";
                        }
                        System.out.println(addId + ". Address: " + key + ", BTC: " + currentPaymentString);
                        
                    }
                    Scanner scanner = new Scanner(System.in);
                    String totalString = String.format("%.12f", total);
                    
                    System.out.println("Total: " + totalString);
                    System.out.println();
                    api.printFeeForTransaction(currentPayment);
                    System.out.println();
                    int chunkUI = (chunk + 1);
                    System.out.println("Prepare to pay for chunk " + chunkUI + " (" + (chunk * PART_SIZE + 1) + "-"
							+ ((chunk + 1)* PART_SIZE ) + ") ...");
                    System.out.print("Are you sure you want to pay " + totalString + " BTC? [y/n]: ");
                    String answer = scanner.nextLine();

                    if (!answer.toLowerCase().equals("y")) {
                        continue;
                    }
					System.out.println("Paying for chunk " + chunkUI + " (" + (chunk * PART_SIZE + 1) + "-"
							+ ((chunk + 1)* PART_SIZE) + ") ...");

                    Withdrawal withdrawal = null;
                    try {
                        withdrawal = api.withdraw(null, null, currentPayment, BlockIO.ParamType.ADDRS, pass);
                        paidChunks.add(chunkUI);
                    } catch (BlockIOException e) {
                        e.printStackTrace();
                        System.out.println("Unable to pay fo this chunk");
                        continue;
                    }

                    System.out.println("Withdrawal done. Transaction ID: " + withdrawal.txid
                            + " Amount withdrawn: " + withdrawal.amountWithdrawn
                            + " Amount sent: " + withdrawal.amountSent
                            + " Network fee: " + withdrawal.networkFee
                            + " Block.io fee: " + withdrawal.blockIOFee);
                    System.out.println("Transaction url: https://chain.so/tx/BTC/" + withdrawal.txid);
                    if (splitPayment.size() == paidChunks.size()) {
                        System.out.println("You've successfully paid for all chunks!");
                        System.out.println("Exiting...");
                        break;
                    }
                }
            }

        }


    }

	private static List splitResults(Map<String, Double> map) {
        List<Map<String, Double>> res = new ArrayList<Map<String, Double>>();
        Map<String, Double> part = new LinkedHashMap<String, Double>();
        for (String key : map.keySet()) {
            part.put(key, map.get(key));
            if (part.size() == PART_SIZE) {
                res.add(part);
                part = new LinkedHashMap<String, Double>();
            }
        }
        if(part.size() > 0) {
            res.add(part);
        }

        return res;
    }
}
