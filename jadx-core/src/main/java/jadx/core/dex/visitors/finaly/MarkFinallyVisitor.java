package jadx.core.dex.visitors.finaly;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.instructions.args.SSAVar;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.trycatch.TryCatchBlockAttr;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.dex.visitors.ConstInlineVisitor;
import jadx.core.dex.visitors.DepthTraversal;
import jadx.core.dex.visitors.IDexTreeVisitor;
import jadx.core.dex.visitors.JadxVisitor;
import jadx.core.dex.visitors.ssa.SSATransform;
import jadx.core.utils.BlockUtils;
import jadx.core.utils.ListUtils;
import jadx.core.utils.Utils;

@JadxVisitor(
		name = "MarkFinallyVisitor",
		desc = "Search and mark duplicate code generated for finally block",
		runAfter = SSATransform.class,
		runBefore = ConstInlineVisitor.class
)
public class MarkFinallyVisitor extends AbstractVisitor {
	private static final Logger LOG = LoggerFactory.getLogger(MarkFinallyVisitor.class);

	@Override
	public void visit(MethodNode mth) {
		if (mth.isNoCode() || mth.isNoExceptionHandlers()) {
			return;
		}
		try {
			boolean applied = false;
			List<TryCatchBlockAttr> tryBlocks = mth.getAll(AType.TRY_BLOCKS_LIST);
			for (TryCatchBlockAttr tryBlock : tryBlocks) {
				applied |= processTryBlock(mth, tryBlock);
			}
			if (applied) {
				mth.clearExceptionHandlers();
				// remove merged or empty try blocks from list in method attribute
				List<TryCatchBlockAttr> clearedTryBlocks = new ArrayList<>(tryBlocks);
				if (clearedTryBlocks.removeIf(tb -> tb.isMerged() || tb.getHandlers().isEmpty())) {
					mth.remove(AType.TRY_BLOCKS_LIST);
					mth.addAttr(AType.TRY_BLOCKS_LIST, clearedTryBlocks);
				}
			}
		} catch (Exception e) {
			LOG.warn("Undo finally extract visitor, mth: {}", mth, e);
			undoFinallyVisitor(mth);
		}
	}

	private static boolean processTryBlock(MethodNode mth, TryCatchBlockAttr tryBlock) {
		if (tryBlock.isMerged()) {
			return false;
		}
		ExceptionHandler allHandler = null;
		InsnNode reThrowInsn = null;
		for (ExceptionHandler excHandler : tryBlock.getHandlers()) {
			if (excHandler.isCatchAll()) {
				allHandler = excHandler;
				for (BlockNode excBlock : excHandler.getBlocks()) {
					InsnNode lastInsn = BlockUtils.getLastInsn(excBlock);
					if (lastInsn != null && lastInsn.getType() == InsnType.THROW) {
						reThrowInsn = BlockUtils.getLastInsn(excBlock);
					}
				}
			}
		}
		if (allHandler != null && reThrowInsn != null) {
			if (extractFinally(mth, tryBlock, allHandler)) {
				reThrowInsn.add(AFlag.DONT_GENERATE);
				return true;
			}
		}
		return false;
	}

	/**
	 * Search and mark common code from 'try' block and 'handlers'.
	 */
	private static boolean extractFinally(MethodNode mth, TryCatchBlockAttr tryBlock, ExceptionHandler allHandler) {
		BlockNode handlerBlock = allHandler.getHandlerBlock();
		List<BlockNode> handlerBlocks =
				new ArrayList<>(BlockUtils.collectBlocksDominatedByWithExcHandlers(mth, handlerBlock, handlerBlock));
		handlerBlocks.remove(handlerBlock); // exclude block with 'move-exception'
		handlerBlocks.removeIf(b -> BlockUtils.checkLastInsnType(b, InsnType.THROW));
		if (handlerBlocks.isEmpty() || BlockUtils.isAllBlocksEmpty(handlerBlocks)) {
			// remove empty catch
			allHandler.getTryBlock().removeHandler(allHandler);
			return true;
		}
		BlockNode startBlock = Utils.getOne(handlerBlock.getCleanSuccessors());
		FinallyExtractInfo extractInfo = new FinallyExtractInfo(allHandler, startBlock, handlerBlocks);

		// collect handlers from this and all inner blocks
		List<ExceptionHandler> handlers = new ArrayList<>();
		collectAllHandlers(tryBlock, handlers);

		// search 'finally' instructions in other handlers
		if (!handlers.isEmpty()) {
			for (ExceptionHandler otherHandler : handlers) {
				if (otherHandler == allHandler) {
					continue;
				}
				for (BlockNode checkBlock : otherHandler.getBlocks()) {
					if (searchDuplicateInsns(checkBlock, extractInfo)) {
						break;
					} else {
						extractInfo.getFinallyInsnsSlice().resetIncomplete();
					}
				}
			}
			if (extractInfo.getDuplicateSlices().size() != handlers.size() - 1) {
				return false;
			}
		}

		// remove 'finally' from 'try' blocks, check all up paths on each exit (connected with finally exit)
		List<BlockNode> tryBlocks = allHandler.getTryBlock().getBlocks();
		BlockNode bottomFinallyBlock = BlockUtils.followEmptyPath(BlockUtils.getBottomBlock(allHandler.getBlocks()));
		BlockNode bottom = BlockUtils.getNextBlock(bottomFinallyBlock);
		if (bottom == null) {
			return false;
		}
		boolean found = false;
		List<BlockNode> pathBlocks = getPathStarts(mth, bottom, bottomFinallyBlock);
		for (BlockNode pred : pathBlocks) {
			List<BlockNode> upPath = BlockUtils.collectPredecessors(mth, pred, tryBlocks);
			if (upPath.size() < handlerBlocks.size()) {
				continue;
			}
			for (BlockNode block : upPath) {
				if (searchDuplicateInsns(block, extractInfo)) {
					found = true;
					break;
				} else {
					extractInfo.getFinallyInsnsSlice().resetIncomplete();
				}
			}
		}
		if (!found) {
			return false;
		}
		if (!checkSlices(extractInfo)) {
			mth.addComment("JADX INFO: finally extract failed");
			return false;
		}

		// 'finally' extract confirmed, apply
		apply(extractInfo);
		allHandler.setFinally(true);

		// merge inner try blocks
		List<TryCatchBlockAttr> innerTryBlocks = tryBlock.getInnerTryBlocks();
		if (!innerTryBlocks.isEmpty()) {
			for (TryCatchBlockAttr innerTryBlock : innerTryBlocks) {
				tryBlock.getHandlers().addAll(innerTryBlock.getHandlers());
				tryBlock.getBlocks().addAll(innerTryBlock.getBlocks());
				innerTryBlock.setMerged(true);
			}
			tryBlock.setBlocks(ListUtils.distinctList(tryBlock.getBlocks()));
			innerTryBlocks.clear();
		}
		return true;
	}

	private static List<BlockNode> getPathStarts(MethodNode mth, BlockNode bottom, BlockNode bottomFinallyBlock) {
		Stream<BlockNode> preds = bottom.getPredecessors().stream().filter(b -> b != bottomFinallyBlock);
		if (bottom == mth.getExitBlock()) {
			preds = preds.flatMap(r -> r.getPredecessors().stream());
		}
		return preds.collect(Collectors.toList());
	}

	private static void collectAllHandlers(TryCatchBlockAttr tryBlock, List<ExceptionHandler> handlers) {
		handlers.addAll(tryBlock.getHandlers());
		for (TryCatchBlockAttr innerTryBlock : tryBlock.getInnerTryBlocks()) {
			collectAllHandlers(innerTryBlock, handlers);
		}
	}

	private static boolean checkSlices(FinallyExtractInfo extractInfo) {
		InsnsSlice finallySlice = extractInfo.getFinallyInsnsSlice();
		List<InsnNode> finallyInsnsList = finallySlice.getInsnsList();

		for (InsnsSlice dupSlice : extractInfo.getDuplicateSlices()) {
			List<InsnNode> dupInsnsList = dupSlice.getInsnsList();
			if (dupInsnsList.size() != finallyInsnsList.size()) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Incorrect finally slice size: {}, expected: {}", dupSlice, finallySlice);
				}
				return false;
			}
		}
		for (int i = 0; i < finallyInsnsList.size(); i++) {
			InsnNode finallyInsn = finallyInsnsList.get(i);
			for (InsnsSlice dupSlice : extractInfo.getDuplicateSlices()) {
				List<InsnNode> insnsList = dupSlice.getInsnsList();
				InsnNode dupInsn = insnsList.get(i);
				if (finallyInsn.getType() != dupInsn.getType()) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("Incorrect finally slice insn: {}, expected: {}", dupInsn, finallyInsn);
					}
					return false;
				}
			}
		}
		return true;
	}

	private static void apply(FinallyExtractInfo extractInfo) {
		markSlice(extractInfo.getFinallyInsnsSlice(), AFlag.FINALLY_INSNS);
		for (InsnsSlice dupSlice : extractInfo.getDuplicateSlices()) {
			markSlice(dupSlice, AFlag.DONT_GENERATE);
		}
		InsnsSlice finallySlice = extractInfo.getFinallyInsnsSlice();
		List<InsnNode> finallyInsnsList = finallySlice.getInsnsList();
		for (int i = 0; i < finallyInsnsList.size(); i++) {
			InsnNode finallyInsn = finallyInsnsList.get(i);
			for (InsnsSlice dupSlice : extractInfo.getDuplicateSlices()) {
				InsnNode dupInsn = dupSlice.getInsnsList().get(i);
				copyCodeVars(finallyInsn, dupInsn);
			}
		}
	}

	private static void markSlice(InsnsSlice slice, AFlag flag) {
		List<InsnNode> insnsList = slice.getInsnsList();
		for (InsnNode insn : insnsList) {
			insn.add(flag);
		}
		for (BlockNode block : slice.getBlocks()) {
			boolean allInsnMarked = true;
			for (InsnNode insn : block.getInstructions()) {
				if (!insn.contains(flag)) {
					allInsnMarked = false;
					break;
				}
			}
			if (allInsnMarked) {
				block.add(flag);
			}
		}
	}

	private static void copyCodeVars(InsnNode fromInsn, InsnNode toInsn) {
		copyCodeVars(fromInsn.getResult(), toInsn.getResult());
		int argsCount = fromInsn.getArgsCount();
		for (int i = 0; i < argsCount; i++) {
			copyCodeVars(fromInsn.getArg(i), toInsn.getArg(i));
		}
	}

	private static void copyCodeVars(InsnArg fromArg, InsnArg toArg) {
		if (fromArg == null || toArg == null
				|| !fromArg.isRegister() || !toArg.isRegister()) {
			return;
		}
		SSAVar fromSsaVar = ((RegisterArg) fromArg).getSVar();
		SSAVar toSsaVar = ((RegisterArg) toArg).getSVar();
		toSsaVar.setCodeVar(fromSsaVar.getCodeVar());
	}

	private static boolean searchDuplicateInsns(BlockNode checkBlock, FinallyExtractInfo extractInfo) {
		boolean isNew = extractInfo.getCheckedBlocks().add(checkBlock);
		if (!isNew) {
			return false;
		}
		BlockNode startBlock = extractInfo.getStartBlock();
		InsnsSlice dupSlice = searchFromFirstBlock(checkBlock, startBlock, extractInfo);
		if (dupSlice == null) {
			return false;
		}
		extractInfo.getDuplicateSlices().add(dupSlice);
		return true;
	}

	private static InsnsSlice searchFromFirstBlock(BlockNode dupBlock, BlockNode startBlock, FinallyExtractInfo extractInfo) {
		InsnsSlice dupSlice = isStartBlock(dupBlock, startBlock, extractInfo);
		if (dupSlice == null) {
			return null;
		}
		if (!dupSlice.isComplete()
				&& !checkBlocksTree(dupBlock, startBlock, dupSlice, extractInfo)) {
			return null;
		}
		return checkTempSlice(dupSlice);
	}

	@Nullable
	private static InsnsSlice checkTempSlice(InsnsSlice slice) {
		List<InsnNode> insnsList = slice.getInsnsList();
		if (insnsList.isEmpty()) {
			return null;
		}
		// ignore slice with only one 'if' insn
		if (insnsList.size() == 1) {
			InsnNode insnNode = insnsList.get(0);
			if (insnNode.getType() == InsnType.IF) {
				return null;
			}
		}
		return slice;
	}

	/**
	 * 'Finally' instructions can start in the middle of the first block.
	 */
	private static InsnsSlice isStartBlock(BlockNode dupBlock, BlockNode finallyBlock, FinallyExtractInfo extractInfo) {
		List<InsnNode> dupInsns = dupBlock.getInstructions();
		List<InsnNode> finallyInsns = finallyBlock.getInstructions();
		if (dupInsns.size() < finallyInsns.size()) {
			return null;
		}
		int startPos = dupInsns.size() - finallyInsns.size();
		int endPos = 0;
		// fast check from end of block
		if (!checkInsns(dupInsns, finallyInsns, startPos)) {
			// check from block start
			if (checkInsns(dupInsns, finallyInsns, 0)) {
				startPos = 0;
				endPos = finallyInsns.size();
			} else {
				// search start insn
				boolean found = false;
				for (int i = 1; i < startPos; i++) {
					if (checkInsns(dupInsns, finallyInsns, i)) {
						startPos = i;
						endPos = finallyInsns.size() + i;
						found = true;
						break;
					}
				}
				if (!found) {
					return null;
				}
			}
		}

		// put instructions into slices
		boolean complete;
		InsnsSlice slice = new InsnsSlice();
		int endIndex;
		if (endPos != 0) {
			endIndex = endPos + 1;
			// both slices completed
			complete = true;
		} else {
			endIndex = dupInsns.size();
			complete = false;
		}

		// fill dup insns slice
		for (int i = startPos; i < endIndex; i++) {
			slice.addInsn(dupInsns.get(i), dupBlock);
		}

		// fill finally insns slice
		InsnsSlice finallySlice = extractInfo.getFinallyInsnsSlice();
		if (finallySlice.isComplete()) {
			// compare slices
			if (finallySlice.getInsnsList().size() != slice.getInsnsList().size()) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Another duplicated slice has different insns count: {}, finally: {}", slice, finallySlice);
				}
				return null;
			}
			// TODO: add additional slices checks
			// and try to extract common part if found difference
		} else {
			for (InsnNode finallyInsn : finallyInsns) {
				finallySlice.addInsn(finallyInsn, finallyBlock);
			}
		}

		if (complete) {
			slice.setComplete(true);
			finallySlice.setComplete(true);
		}
		return slice;
	}

	private static boolean checkInsns(List<InsnNode> remInsns, List<InsnNode> finallyInsns, int delta) {
		for (int i = finallyInsns.size() - 1; i >= 0; i--) {
			InsnNode startInsn = finallyInsns.get(i);
			InsnNode remInsn = remInsns.get(delta + i);
			if (!sameInsns(remInsn, startInsn)) {
				return false;
			}
		}
		return true;
	}

	private static boolean checkBlocksTree(BlockNode dupBlock, BlockNode finallyBlock,
			InsnsSlice dupSlice, FinallyExtractInfo extractInfo) {
		InsnsSlice finallySlice = extractInfo.getFinallyInsnsSlice();

		List<BlockNode> finallyCS = getSuccessorsWithoutLoop(finallyBlock);
		List<BlockNode> dupCS = getSuccessorsWithoutLoop(dupBlock);
		if (finallyCS.size() == dupCS.size()) {
			for (int i = 0; i < finallyCS.size(); i++) {
				BlockNode finSBlock = finallyCS.get(i);
				BlockNode dupSBlock = dupCS.get(i);
				if (extractInfo.getAllHandlerBlocks().contains(finSBlock)) {
					if (!compareBlocks(dupSBlock, finSBlock, dupSlice, extractInfo)) {
						return false;
					}
					if (!checkBlocksTree(dupSBlock, finSBlock, dupSlice, extractInfo)) {
						return false;
					}
					dupSlice.addBlock(dupSBlock);
					finallySlice.addBlock(finSBlock);
				}
			}
		}
		dupSlice.setComplete(true);
		finallySlice.setComplete(true);
		return true;
	}

	private static List<BlockNode> getSuccessorsWithoutLoop(BlockNode block) {
		if (block.contains(AFlag.LOOP_END)) {
			return block.getCleanSuccessors();
		}
		return block.getSuccessors();
	}

	private static boolean compareBlocks(BlockNode dupBlock, BlockNode finallyBlock, InsnsSlice dupSlice, FinallyExtractInfo extractInfo) {
		List<InsnNode> dupInsns = dupBlock.getInstructions();
		List<InsnNode> finallyInsns = finallyBlock.getInstructions();
		int dupInsnCount = dupInsns.size();
		int finallyInsnCount = finallyInsns.size();
		if (finallyInsnCount == 0) {
			return dupInsnCount == 0;
		}
		if (dupInsnCount < finallyInsnCount) {
			return false;
		}
		for (int i = 0; i < finallyInsnCount; i++) {
			if (!sameInsns(dupInsns.get(i), finallyInsns.get(i))) {
				return false;
			}
		}
		if (dupInsnCount > finallyInsnCount) {
			dupSlice.addInsns(dupBlock, 0, finallyInsnCount);
			dupSlice.setComplete(true);
			InsnsSlice finallyInsnsSlice = extractInfo.getFinallyInsnsSlice();
			finallyInsnsSlice.addBlock(finallyBlock);
			finallyInsnsSlice.setComplete(true);
		}
		return true;
	}

	private static boolean sameInsns(InsnNode remInsn, InsnNode fInsn) {
		if (!remInsn.isSame(fInsn)) {
			return false;
		}
		// TODO: check instance arg in ConstructorInsn
		// TODO: compare literals
		for (int i = 0; i < remInsn.getArgsCount(); i++) {
			InsnArg remArg = remInsn.getArg(i);
			InsnArg fArg = fInsn.getArg(i);
			if (remArg.isRegister() != fArg.isRegister()) {
				return false;
			}
			boolean remConst = remArg.isConst();
			if (remConst != fArg.isConst()) {
				return false;
			}
			if (remConst && !remArg.isSameConst(fArg)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Reload method without applying this visitor
	 */
	private static void undoFinallyVisitor(MethodNode mth) {
		try {
			// TODO: make more common and less hacky
			mth.unload();
			mth.load();
			for (IDexTreeVisitor visitor : mth.root().getPasses()) {
				if (visitor instanceof MarkFinallyVisitor) {
					break;
				}
				DepthTraversal.visit(visitor, mth);
			}
		} catch (Exception e) {
			LOG.error("Undo finally extract failed, mth: {}", mth, e);
		}
	}
}
