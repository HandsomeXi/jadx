package jadx.core.dex.visitors;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.input.data.ICatch;
import jadx.api.plugins.input.data.ITry;
import jadx.api.plugins.utils.Utils;
import jadx.core.Consts;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.trycatch.CatchAttr;
import jadx.core.dex.trycatch.ExcHandlerAttr;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.utils.exceptions.JadxException;

import static jadx.core.dex.visitors.ProcessInstructionsVisitor.getNextInsnOffset;

@JadxVisitor(
		name = "Attach Try/Catch Visitor",
		desc = "Attach try/catch info to instructions",
		runBefore = {
				ProcessInstructionsVisitor.class
		}
)
public class AttachTryCatchVisitor extends AbstractVisitor {
	private static final Logger LOG = LoggerFactory.getLogger(AttachTryCatchVisitor.class);

	@Override
	public void visit(MethodNode mth) throws JadxException {
		if (mth.isNoCode()) {
			return;
		}
		initTryCatches(mth, mth.getInstructions(), mth.getCodeReader().getTries());
	}

	private static void initTryCatches(MethodNode mth, InsnNode[] insnByOffset, List<ITry> tries) {
		if (tries.isEmpty()) {
			return;
		}
		if (Consts.DEBUG_EXC_HANDLERS) {
			LOG.debug("Raw try blocks in {}", mth);
			tries.forEach(tryData -> LOG.debug(" - {}", tryData));
		}
		for (ITry tryData : tries) {
			List<ExceptionHandler> handlers = attachHandlers(mth, tryData.getCatch(), insnByOffset);
			if (handlers.isEmpty()) {
				continue;
			}
			markTryBounds(insnByOffset, tryData, new CatchAttr(handlers));
		}
	}

	private static void markTryBounds(InsnNode[] insnByOffset, ITry aTry, CatchAttr catchAttr) {
		int offset = aTry.getStartOffset();
		int end = aTry.getEndOffset();

		boolean tryBlockStarted = false;
		InsnNode insn = null;
		while (offset <= end) {
			InsnNode insnAtOffset = insnByOffset[offset];
			if (insnAtOffset != null) {
				insn = insnAtOffset;
				insn.addAttr(catchAttr);
				if (!tryBlockStarted) {
					insn.add(AFlag.TRY_ENTER);
					tryBlockStarted = true;
				}
			}
			offset = getNextInsnOffset(insnByOffset, offset);
			if (offset == -1) {
				break;
			}
		}
		if (tryBlockStarted) {
			insn.add(AFlag.TRY_LEAVE);
		} else {
			// no instructions found in range -> add nop at start offset
			InsnNode nop = insertNOP(insnByOffset, aTry.getStartOffset());
			nop.add(AFlag.TRY_ENTER);
			nop.add(AFlag.TRY_LEAVE);
			nop.addAttr(catchAttr);
		}
	}

	private static List<ExceptionHandler> attachHandlers(MethodNode mth, ICatch catchBlock, InsnNode[] insnByOffset) {
		int[] handlerOffsetArr = catchBlock.getHandlers();
		String[] handlerTypes = catchBlock.getTypes();

		int handlersCount = handlerOffsetArr.length;
		List<ExceptionHandler> list = new ArrayList<>(handlersCount);
		for (int i = 0; i < handlersCount; i++) {
			int handlerOffset = handlerOffsetArr[i];
			ClassInfo type = ClassInfo.fromName(mth.root(), handlerTypes[i]);
			Utils.addToList(list, createHandler(mth, insnByOffset, handlerOffset, type));
		}
		int allHandlerOffset = catchBlock.getCatchAllHandler();
		if (allHandlerOffset >= 0) {
			Utils.addToList(list, createHandler(mth, insnByOffset, allHandlerOffset, null));
		}
		return list;
	}

	@Nullable
	private static ExceptionHandler createHandler(MethodNode mth, InsnNode[] insnByOffset, int handlerOffset, @Nullable ClassInfo type) {
		InsnNode insn = insnByOffset[handlerOffset];
		if (insn != null) {
			ExcHandlerAttr excHandlerAttr = insn.get(AType.EXC_HANDLER);
			if (excHandlerAttr != null) {
				ExceptionHandler handler = excHandlerAttr.getHandler();
				if (handler.addCatchType(type)) {
					// exist handler updated (assume from same try block) - don't add again
					return null;
				}
				// same handler (can be used in different try blocks)
				return handler;
			}
		} else {
			insn = insertNOP(insnByOffset, handlerOffset);
		}
		ExceptionHandler handler = new ExceptionHandler(handlerOffset, type);
		mth.addExceptionHandler(handler);
		insn.addAttr(new ExcHandlerAttr(handler));
		return handler;
	}

	private static InsnNode insertNOP(InsnNode[] insnByOffset, int offset) {
		InsnNode nop = new InsnNode(InsnType.NOP, 0);
		nop.setOffset(offset);
		nop.add(AFlag.SYNTHETIC);
		insnByOffset[offset] = nop;
		return nop;
	}
}
