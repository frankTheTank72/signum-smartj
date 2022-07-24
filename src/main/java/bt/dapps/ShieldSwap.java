package bt.dapps;

import bt.Address;
import bt.BT;
import bt.Contract;
import bt.Emulator;
import bt.Register;
import bt.Timestamp;
import bt.Transaction;
import bt.ui.EmulatorWindow;

/**
 * An Automated Market Maker (AMM) or liquidity pool smart contract.
 * 
 * There is a pair of token X and token Y that are kept inside the contract.
 * 
 * When adding liquidity an investor gets back the token XY. This token can
 * be used just as a regular token. The investor can later remove liquidity
 * by sending back the XY tokens.
 * 
 * About the liquidity token, XY, it is issued by the contract when it first
 * runs. Be sure to send at least the SIGNA amount for the token issuance fee
 * in a first transaction.
 * 
 * New tokens are minted by code when liquidity is added and burnt when liquidity is
 * removed.
 * 
 * The code is "shielded", not allowing the "sandwich attack", with all swaps
 * in a given block paying the same price and all liquidity addition/removal going
 * before any trades.
 * 
 * @author jjos
 *
 */
public class ShieldSwap extends Contract {
	
	long name;
	long decimalPlaces;
	
	long tokenX;
	static long tokenY;
	
	Address platformContract;
	Address tracker;
	
	long swapFeeDiv = Long.MAX_VALUE;
	long platformFeeDiv = Long.MAX_VALUE;
	
	long tokenXY;
	long reserveX;
	long reserveY;
	long totalSupply;
	
	long reserveXBlock, reserveYBlock;
	long priceMaxX, priceMaxY, price;
	Timestamp lastProcessedLiquidity;
	Timestamp lastProcessedSwapCheck;
	Timestamp lastProcessedSwap;
	long platformFee;
	long platformFeeBlockX;
	long platformFeeBlockY;
	
	Transaction tx;
	Register arguments;
	boolean txApproved;
	long minOut;
	
	// temporary variables
	long dx, dy;
	long liquidity;
	long liquidity2;
	long fee, x1, y1;
	
	// We want the sqrt, so power is 0.5 = 5000_0000 / 10000_0000;
	private static final long SQRT_POW = 5000_0000;

	private static final long KEY_PROCESS_SWAP = 0;

	private static final long ADD_LIQUIDITY_METHOD = 1;
	private static final long REMOVE_LIQUIDITY_METHOD = 2;
	private static final long SWAP_XY_METHOD = 3;
	private static final long SWAP_YX_METHOD = 4;
			
	public ShieldSwap() {
		// constructor, runs when the first TX arrives
		tokenXY = issueAsset(name, 0L, decimalPlaces);
	}
	
	/**
	 * We process all the swap transactions that will be approved so all swaps will pay the same price.
	 * 
	 * This avoids the "sandwich attack" present in most liquidity pools available today.
	 */
	@Override
	protected void blockStarted() {		
		// First we iterate to add/remove liquidity
		while(true) {
			tx = getTxAfterTimestamp(lastProcessedLiquidity);
			if(tx == null) {
				break;
			}
			lastProcessedLiquidity = tx.getTimestamp();
			arguments = tx.getMessage();
			
			if(arguments.getValue1() == ADD_LIQUIDITY_METHOD) {
				dx = tx.getAmount(tokenX);
				dy = tx.getAmount(tokenY);
				
				if(totalSupply == 0) {
					liquidity = calcPow(dx, SQRT_POW)*calcPow(dy, SQRT_POW);
				}
				else {
					liquidity = calcMultDiv(dx, totalSupply, reserveX);
					liquidity2 = calcMultDiv(dy, totalSupply, reserveY);
					if(liquidity2 < liquidity)
						liquidity = liquidity2;
				}
				
				mintAsset(tokenXY, liquidity);
				sendAmount(tokenXY, liquidity, tx.getSenderAddress());
				
				totalSupply = totalSupply + liquidity;
				reserveX += dx;
				reserveY += dy;
			}
			else if(arguments.getValue1() == REMOVE_LIQUIDITY_METHOD) {
		        liquidity = tx.getAmount(tokenXY);

		        dx = calcMultDiv(liquidity, reserveX, totalSupply);
		        dy = calcMultDiv(liquidity, reserveY, totalSupply);

		        totalSupply = totalSupply - liquidity;
		        reserveX -= dx;
		        reserveY -= dy;
		        
		        sendAmount(tokenX, dx, tx.getSenderAddress());
		        sendAmount(tokenY, dy, tx.getSenderAddress());
		        
		        // burn the XY token
		        sendAmount(tokenXY, liquidity, getAddress(0));
		    }
		}

		// Now we iterate to check which swaps should be accepted and what should be
		// the reserve changes within the block
		reserveXBlock = reserveX;
		reserveYBlock = reserveY;
		priceMaxX = 0;
		priceMaxY = 0;
		platformFeeBlockX = 0;
		platformFeeBlockY = 0;
		while(true) {
			tx = getTxAfterTimestamp(lastProcessedSwapCheck);
			if(tx == null) {
				break;
			}
			lastProcessedSwapCheck = tx.getTimestamp();
			
			if(liquidity == 0) {
				// no liquidity to operate
				continue;
			}
			txApproved = false;
			arguments = tx.getMessage();
			minOut = arguments.getValue2();
			if(minOut > 0) {
				if(arguments.getValue1() == SWAP_XY_METHOD) {
					dx = tx.getAmount(tokenX);
					fee = dx/swapFeeDiv;
					platformFee = dx/platformFeeDiv;
					x1 = reserveXBlock + dx;
					y1 = calcMultDiv(reserveXBlock, reserveYBlock, x1 - fee - platformFee);

					dy = y1 - reserveYBlock;
					price = calcMultDiv(dx, reserveY, minOut);
					
					if(-dy >= minOut && price > 0) {
						if (priceMaxX == 0) {
							priceMaxX = price;
						}
						if (price <= priceMaxX){
							txApproved = true;
							platformFeeBlockX += platformFee;
						}
					}
				}
				else if(arguments.getValue1() == SWAP_YX_METHOD) {
					dy = tx.getAmount(tokenY);
					
					fee = dy/swapFeeDiv;
					platformFee = dy/platformFeeDiv;
					y1 = reserveYBlock + dy;
					x1 = calcMultDiv(reserveXBlock, reserveYBlock, y1 - fee - platformFee);
					
					dx = x1 - reserveXBlock;
					price = calcMultDiv(dy, reserveX, minOut);

					if(-dx >= minOut && price > 0) {
						if (priceMaxY == 0) {
							priceMaxY = price;
						}
						if (price <= priceMaxY){
							txApproved = true;
							platformFeeBlockY += platformFee;
						}
					}
				}
				
				if(txApproved) {
					// Update the amount exchanged and store the tx as processed
					reserveXBlock += dx;
					reserveYBlock += dy;
					setMapValue(tx.getId(), KEY_PROCESS_SWAP, minOut);
				}
			}			
		}
		
		// finally, we execute the accepted swaps with the liquid changes, all paying the same price
		while(true) {
			tx = getTxAfterTimestamp(lastProcessedSwap);
			if(tx == null) {
				break;
			}
			lastProcessedSwap = tx.getTimestamp();
			
			arguments = tx.getMessage();
			minOut = arguments.getValue2();
			if(arguments.getValue1() == SWAP_XY_METHOD || arguments.getValue1() == SWAP_YX_METHOD) {
				if(getMapValue(tx.getId(), KEY_PROCESS_SWAP) == 0) {
					// this swap was not approved, refund
					sendAmount(tokenX, tx.getAmount(tokenX), tx.getSenderAddress());
					sendAmount(tokenY, tx.getAmount(tokenY), tx.getSenderAddress());
				}
				else {
					if(arguments.getValue1() == SWAP_XY_METHOD) {
						dx = tx.getAmount(tokenX);
						fee = dx/swapFeeDiv + dx/platformFeeDiv;
						dx -= fee;
						dy = calcMultDiv(-dx, reserveY, reserveXBlock);
							
						sendAmount(tokenY, -dy, tx.getSenderAddress());
					}
					else {
						// swap YX
						dy = tx.getAmount(tokenY);
						
						fee = dy/swapFeeDiv + dy/platformFeeDiv;
						dy -= fee;
						dx = calcMultDiv(-dy, reserveX, reserveYBlock);
						
						sendAmount(tokenX, -dx, tx.getSenderAddress());
					}
					// send a message to more easily track the trades and prices
					sendMessage(tx.getId(), arguments.getValue1(), dx, dy, tracker);
				}
			}
		}
		if(platformFeeBlockX > 0) {
			sendAmount(tokenX, platformFeeBlockX, platformContract);
		}
		if(platformFeeBlockY > 0) {
			sendAmount(tokenY, platformFeeBlockY, platformContract);
		}
		
		// update the reserves when the block finishes to reconcile any dust/revenue
		reserveX = this.getCurrentBalance(tokenX);
		reserveY = this.getCurrentBalance(tokenY);
	}

	@Override
	public void txReceived() {
		// do nothing
	}
	
	public static void main(String[] args) throws Exception {
		BT.activateSIP37(true);
		
		Emulator emu = Emulator.getInstance();
		
		Address buyer1 = emu.getAddress("BUYER1");
		emu.airDrop(buyer1, 1000*Contract.ONE_SIGNA);
		Address buyer2 = emu.getAddress("BUYER2");
		emu.airDrop(buyer2, 1000*Contract.ONE_SIGNA);

		Address lpProvider = emu.getAddress("LP_PROVIDER");
		emu.airDrop(lpProvider, 10000*Contract.ONE_SIGNA);
		long BTC_ASSET_ID = emu.issueAsset(lpProvider, 11111, 0, 8);
		ShieldSwap.tokenY = BTC_ASSET_ID;
		emu.mintAsset(lpProvider, BTC_ASSET_ID, 2*Contract.ONE_SIGNA);
		
		Address lp = Emulator.getInstance().getAddress("LP");
		emu.createConctract(lpProvider, lp, ShieldSwap.class, Contract.ONE_SIGNA);
		emu.forgeBlock();

		long reserveX = 10000*Contract.ONE_SIGNA;
		long reserveY = 2*Contract.ONE_SIGNA;
		emu.send(lpProvider, lp, reserveX, BTC_ASSET_ID, reserveY, Register.newInstance(ADD_LIQUIDITY_METHOD, 0, 0, 0));
		emu.forgeBlock();		
		
		long sendX = 100*Contract.ONE_SIGNA;
		long expectedY = (long)(reserveY * sendX * 0.9) / reserveX ;
		
		emu.send(buyer1, lp, sendX,	Register.newInstance(SWAP_XY_METHOD, expectedY, 0, 0));
		emu.send(buyer2, lp, sendX,	Register.newInstance(SWAP_XY_METHOD, expectedY, 0, 0));
		emu.forgeBlock();
		emu.forgeBlock();
		
		new EmulatorWindow(ShieldSwap.class);
	}
	
}
