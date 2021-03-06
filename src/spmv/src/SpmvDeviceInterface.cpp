#include <Spark/device/SpmvDeviceInterface.h>

// Make sure Spmv.h is on the compilation path
#include <GeneratedImplemntations.h>

int getSpmv_InputWidth() {
  return Spmv_inputWidth;
}

int getSpmv_NumPipes() {
  return Spmv_numPipes;
}

int getSpmv_MaxRows() {
  return Spmv_maxRows;
}

int getSpmv_PartitionSize() {
  return Spmv_cacheSize;
}

bool getDramReductionEnabled() {
  return Spmv_dramReductionEnabled == 1;
}
