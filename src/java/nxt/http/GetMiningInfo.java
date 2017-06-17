package nxt.http;

import java.nio.ByteBuffer;

import javax.servlet.http.HttpServletRequest;

import nxt.Block;
import nxt.Constants;
import nxt.Nxt;
import nxt.util.Convert;
import fr.cryptohash.Shabal256;

import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

public final class GetMiningInfo extends APIServlet.APIRequestHandler {
	static final GetMiningInfo instance = new GetMiningInfo();
	
	private GetMiningInfo() {
		super(new APITag[] {APITag.MINING, APITag.INFO});
	}
	
	@Override
	JSONStreamAware processRequest(HttpServletRequest req) {
		JSONObject response = new JSONObject();
		
		response.put("height", Long.toString(Nxt.getBlockchain().getHeight() + 1));
		
		Block lastBlock = Nxt.getBlockchain().getLastBlock();
		byte[] lastGenSig = lastBlock.getGenerationSignature();
		Long lastGenerator = lastBlock.getGeneratorId();
		byte[] transactionHash = Nxt.getBlockchainProcessor().getTransactionHash(lastBlock);
		
		ByteBuffer buf;
		if (lastBlock.getHeight() >= Constants.TRANSACTION_HASH_IN_GENERATION_SIG_BLOCK) {
			buf = ByteBuffer.allocate(32 + 8 + 32);
			buf.put(lastGenSig);
			buf.putLong(lastGenerator);
			buf.put(transactionHash);
		} else {
			buf = ByteBuffer.allocate(32 + 8);
			buf.put(lastGenSig);
			buf.putLong(lastGenerator);
		}
		
		Shabal256 md = new Shabal256();
		md.update(buf.array());
		byte[] newGenSig = md.digest();
		
		response.put("generationSignature", Convert.toHexString(newGenSig));
		response.put("baseTarget", Long.toString(lastBlock.getBaseTarget()));
		
		return response;
	}
}
