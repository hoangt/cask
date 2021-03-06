import com.maxeler.maxcompiler.v2.managers.custom.CustomManager;
import com.maxeler.maxcompiler.v2.managers.custom.DFELink;
import com.maxeler.maxcompiler.v2.managers.custom.blocks.*;
import com.maxeler.maxcompiler.v2.managers.custom.stdlib.MemoryControlGroup;
import com.maxeler.maxcompiler.v2.managers.engine_interfaces.*;
import com.maxeler.maxcompiler.v2.managers.BuildConfig;
import com.maxeler.maxcompiler.v2.statemachine.manager.ManagerStateMachine;

import com.custom_computing_ic.dfe_snippets.manager.*;

public class SpmvManager extends CustomManager{

    private final int cacheSize;
    private final int inputWidth;
    private final int maxRows;
    private final int numPipes;

    // parameters of CSR format used: float64 values, int32 index.
    private static final int mantissaWidth = 53;
    private static final int indexWidth = 32;

    private static final int FLOATING_POINT_LATENCY = 16;

    private static final boolean DBG_PAR_CSR_CTL = false;
    private static final boolean DBG_SPMV_KERNEL = false;
    private static final boolean DBG_REDUCTION_KERNEL = false;
    private static final boolean dramReductionEnabled = false;


    SpmvManager(SpmvEngineParams ep) {
        super(ep);

        inputWidth = ep.getInputWidth();
        cacheSize = ep.getVectorCacheSize();
        maxRows = ep.getMaxRows();
        numPipes = ep.getNumPipes();

        if (384 % inputWidth != 0) {
          throw new RuntimeException("Error! 384 is not a multiple of INPUT WIDTH: " +
              "This may lead to stalls due to padding / unpadding ");
        }

        addMaxFileConstant("inputWidth", inputWidth);
        addMaxFileConstant("cacheSize", cacheSize);
        addMaxFileConstant("maxRows", maxRows);
        addMaxFileConstant("numPipes", numPipes);
        addMaxFileConstant("dramReductionEnabled", dramReductionEnabled ? 1 : 0);

        ManagerUtils.setDRAMMaxDeviceFrequency(this, ep);
        config.setAllowNonMultipleTransitions(true);

        for (int i = 0; i < numPipes; i++)
          addComputePipe(i, inputWidth);
    }


    void addComputePipe(int id, int inputWidth) {
        StateMachineBlock readControlBlock = addStateMachine(
            getReadControl(id),
            new ParallelCsrReadControl(this, inputWidth, DBG_PAR_CSR_CTL));

        readControlBlock.getInput("length") <== addUnpaddingKernel("colptr" + id, 32, id * 10 + 1);

        KernelBlock k = addKernel(new SpmvKernel(
              makeKernelParameters(getComputeKernel(id)),
              inputWidth,
              cacheSize,
              indexWidth,
              mantissaWidth,
              DBG_SPMV_KERNEL
              ));

        k.getInput("indptr_values") <== addUnpaddingKernel("indptr_values" + id, inputWidth * 96, id * 10 + 2);
        k.getInput("vromLoad") <== addUnpaddingKernel("vromLoad" + id, 64, id * 10 + 3);
        k.getInput("control") <== readControlBlock.getOutput("control");

        KernelBlock r = null;
        if (dramReductionEnabled) {
          r = addKernel(new DramSpmvReductionKernel(makeKernelParameters(getReductionKernel(id))));

          r.getInput("prevb") <== addUnpaddingKernel("prevb" + id, 64, id * 10 + 5);

        }
        else {
          r = addKernel(new BramSpmvReductionKernel(
                makeKernelParameters(getReductionKernel(id)),
                FLOATING_POINT_LATENCY,
                maxRows,
                DBG_REDUCTION_KERNEL));
        }

        addPaddingKernel("reductionOut" + id) <== r.getOutput("reductionOut");
        r.getInput("reductionIn") <== k.getOutput("output");
        r.getInput("skipCount") <== k.getOutput("skipCount");

    }

    DFELink addPaddingKernel(String stream)
    {
      System.out.println("Creating kernel  " + getPaddingKernel(stream));
      KernelBlock p = addKernel(new PaddingKernel(
            makeKernelParameters(getPaddingKernel(stream))));
      addStreamToOnCardMemory(stream,
          MemoryControlGroup.MemoryAccessPattern.LINEAR_1D) <== p.getOutput("paddingOut");
      return p.getInput("paddingIn");
    }

    DFELink addUnpaddingKernel(
        String stream,
        int bitwidth,
        int id)
   {
     KernelBlock k = addKernel(new
         UnpaddingKernel(makeKernelParameters(getUnpaddingKernel(stream)), bitwidth, id));
     k.getInput("paddingIn") <== addStreamFromOnCardMemory(stream,
         MemoryControlGroup.MemoryAccessPattern.LINEAR_1D);
     return k.getOutput("pout");
   }

    String getComputeKernel(int id) {
      return "computeKernel" + id;
    }

    String getReductionKernel(int id) {
      return "reductionKernel" + id;
    }

    String getPaddingKernel(String id) {
      return "padding_" + id;
    }

    String getReadControl(int id) {
      return "readControl" + id;
    }

    String getFanoutName(int id) {
      return "flush" + id;
    }

    String getUnpaddingKernel(String stream) {
      return "unpadding_" + stream;
    }

    void setUpComputePipe(
        EngineInterface ei, int id,
        InterfaceParam vectorLoadCycles,
        InterfaceParam nPartitions,
        InterfaceParam n,
        InterfaceParam outResultStartAddress,
        InterfaceParam outResultSize,
        InterfaceParam vStartAddress,
        InterfaceParam colPtrStartAddress,
        InterfaceParam colptrSize,
        InterfaceParam indptrValuesStartAddress,
        InterfaceParam indptrValuesSize,
        InterfaceParam totalCycles,
        InterfaceParam reductionCycles,
        InterfaceParam nIterations) {

      String computeKernel = getComputeKernel(id);
      String reductionKernel = getReductionKernel(id);
      String paddingKernel = getPaddingKernel("" + id);
      String readControl = getReadControl(id);

      ei.setTicks(computeKernel, totalCycles * nIterations);

      ei.setScalar(computeKernel, "vectorLoadCycles", vectorLoadCycles);

      ei.setTicks(reductionKernel, reductionCycles * nIterations);
      ei.setScalar(reductionKernel, "nRows", n);
      ei.setScalar(reductionKernel, "totalCycles", reductionCycles);


      String stream = "vromLoad" + id;
      setupUnpaddingKernel(
          ei,
          getUnpaddingKernel(stream),
          stream,
          vectorLoadCycles * nPartitions,
          ei.addConstant(8), // XXX magic constant...
          nIterations,
          vStartAddress
          );

      InterfaceParam colptrEntries = colptrSize / CPUTypes.INT32.sizeInBytes();
      stream = "colptr" + id;
      setupUnpaddingKernel(
          ei,
          getUnpaddingKernel(stream),
          stream,
          colptrEntries,
          ei.addConstant(4),
          nIterations,
          colPtrStartAddress);

      InterfaceParam valueEntries = indptrValuesSize / (8 + 4) / inputWidth; // 12 bytes per entry
      stream = "indptr_values" + id;
      setupUnpaddingKernel(
          ei,
          getUnpaddingKernel(stream),
          stream,
          valueEntries,
          inputWidth * ei.addConstant(8 + 4),
          nIterations,
          indptrValuesStartAddress);

      InterfaceParam zero = ei.addConstant(0l);

      ei.setScalar(readControl, "nrows", n);
      ei.setScalar(readControl, "vectorLoadCycles", vectorLoadCycles);
      ei.setScalar(readControl, "nPartitions", nPartitions);
      ei.setScalar(readControl, "nIterations", nIterations);

      InterfaceParam spmvOutputWidthBytes = ei.addConstant(CPUTypes.DOUBLE.sizeInBytes());
      InterfaceParam spmvOutputSizeBytes = n * spmvOutputWidthBytes;

      InterfaceParam size = nIterations;
      if (dramReductionEnabled) {
        setupUnpaddingKernel(ei,
            getUnpaddingKernel("prevb" + id),
            "prevb" + id,
            n,
            ei.addConstant(8),
            nIterations * nPartitions,
            outResultStartAddress
            );
        size *= nPartitions;
      }

      stream = "reductionOut" + id;
      setupUnpaddingKernel(ei,
          getPaddingKernel(stream),
          stream,
          n,
          ei.addConstant(8),
          size,
          outResultStartAddress);
    }

    /**
     * An unpadding kernel reads a stream from memory and discards the bytes
     * which were added to pad the data to a multiple of BURST_SIZE_BYTES.
     *
     * @param size size in number of elements of the stream
     * @param memoryStream the stream from memory to unpad
     * @param inputWidthBytes the number of bytes the kernel should read (and
     *                        write) every cycle
     */
    private void setupUnpaddingKernel(
        EngineInterface ei,
        String kernelId,
        String memoryStream,
        InterfaceParam size,
        InterfaceParam inputWidthBytes,
        InterfaceParam iterations,
        InterfaceParam startAddress
        ) {

      InterfaceParam unpaddingCycles = getPaddingCycles(size * inputWidthBytes, inputWidthBytes);
      InterfaceParam totalSize = unpaddingCycles + size;
      System.out.println("Setting ticks for kernel  " + kernelId);

      ei.setTicks(kernelId, totalSize * iterations);
      ei.setScalar(kernelId, "nInputs", size);
      ei.setScalar(kernelId, "totalCycles", totalSize);

      ei.setLMemLinearWrapped(memoryStream,
          startAddress,
          totalSize * inputWidthBytes,
          totalSize * inputWidthBytes * iterations,
          ei.addConstant(0));
    }

    private InterfaceParam smallestMultipleLargerThan(InterfaceParam x, long y) {
      return
        y * (x / y +
          InterfaceMath.max(
            0l, InterfaceMath.min(x % y, 1l)));
    }

    private InterfaceParam getPaddingCycles(
        InterfaceParam outSizeBytes,
        InterfaceParam outWidthBytes)
    {
      final int burstSizeBytes = 384;
      InterfaceParam writeSize = smallestMultipleLargerThan(outSizeBytes, burstSizeBytes);
      return (writeSize - outSizeBytes) / outWidthBytes;
    }

    private EngineInterface interfaceDefault() {
      EngineInterface ei = new EngineInterface();

      InterfaceParam vectorLoadCycles = ei.addParam("vectorLoadCycles", CPUTypes.INT);
      InterfaceParam nPartitions = ei.addParam("nPartitions", CPUTypes.INT);
      InterfaceParam nIterations = ei.addParam("nIterations", CPUTypes.INT);
      InterfaceParamArray nrows = ei.addParamArray("nrows", CPUTypes.INT32);

      InterfaceParamArray outStartAddresses = ei.addParamArray("outStartAddresses", CPUTypes.INT64);
      InterfaceParamArray outResultSizes = ei.addParamArray("outResultSizes", CPUTypes.INT32);

      InterfaceParamArray totalCycles = ei.addParamArray("totalCycles", CPUTypes.INT32);
      InterfaceParamArray vStartAddresses = ei.addParamArray("vStartAddresses", CPUTypes.INT64);

      InterfaceParamArray indptrValuesAddresses = ei.addParamArray("indptrValuesAddresses", CPUTypes.INT64);
     InterfaceParamArray indptrValuesSizes = ei.addParamArray("indptrValuesSizes", CPUTypes.INT32);

      InterfaceParamArray colptrStartAddresses = ei.addParamArray("colPtrStartAddresses", CPUTypes.INT64);
      InterfaceParamArray colptrSizes = ei.addParamArray("colptrSizes", CPUTypes.INT32);

      InterfaceParamArray reductionCycles = ei.addParamArray("reductionCycles", CPUTypes.INT32);

      for (int i = 0; i < numPipes; i++)
        setUpComputePipe(ei, i,
            vectorLoadCycles,
            nPartitions,
            nrows.get(i),
            outStartAddresses.get(i),
            outResultSizes.get(i),
            vStartAddresses.get(i),
            colptrStartAddresses.get(i),
            colptrSizes.get(i),
            indptrValuesAddresses.get(i),
            indptrValuesSizes.get(i),
            totalCycles.get(i),
            reductionCycles.get(i),
            nIterations);

      ei.ignoreLMem("cpu2lmem");
      ei.ignoreStream("fromcpu");
      ei.ignoreStream("tocpu");
      ei.ignoreLMem("lmem2cpu");
      return ei;
    }

    public static void main(String[] args) {

      SpmvManager manager = new SpmvManager(new SpmvEngineParams(args));
      //ManagerUtils.debug(manager);
      manager.createSLiCinterface(ManagerUtils.dramWrite(manager));
      manager.createSLiCinterface(ManagerUtils.dramRead(manager));
      manager.createSLiCinterface(manager.interfaceDefault());
      ManagerUtils.setFullBuild(manager, BuildConfig.Effort.HIGH, 2, 2);
      manager.build();
    }
}
