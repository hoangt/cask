#include <Spark/Spmv.hpp>
#include <Spark/SimpleSpmv.hpp>

#include <Spark/converters.hpp>
#include <iostream>
#include <tuple>

#include <dfesnippets/VectorUtils.hpp>
#include <Maxfiles.h>

using namespace spark::spmv;
using ssarch = spark::spmv::SimpleSpmvArchitecture;

// how many cycles does it take to resolve the accesses
int ssarch::cycleCount(int32_t* v, int size, int inputWidth)
{
  int cycles = 0;
  int crtPos = 0;
  for (int i = 0; i < size; i++) {
    int toread = v[i] - (i > 0 ? v[i - 1] : 0);
    do {
      int canread = std::min(inputWidth - crtPos, toread);
      crtPos += canread;
      crtPos %= inputWidth;
      cycles++;
      toread -= canread;
    } while (toread > 0);
  }
  return cycles;
}

// transform a given matrix with n rows in blocks of size n X blockSize
spark::spmv::BlockingResult ssarch::do_blocking(
    const EigenSparseMatrix m,
    int blockSize,
    int inputWidth)
{

  const int* indptr = m.innerIndexPtr();
  const double* values = m.valuePtr();
  const int* colptr = m.outerIndexPtr();
  int rows = m.rows();
  int cols = m.cols();
  int n = rows;
  std::cout << "Mat rows " << n << std::endl;

  int nPartitions = cols / blockSize + (cols % blockSize == 0 ? 0 : 1);
  //std::cout << "Npartitions: " << nPartitions << std::endl;

  std::vector<spark::sparse::CsrMatrix> partitions(nPartitions);

  for (int i = 0; i < rows; i++) {
    for (int j = 0; j < nPartitions; j++) {
      auto& p = std::get<0>(partitions[j]);
      if (p.size() == 0)
        p.push_back(0);
      else
        p.push_back(p.back());
    }
    //std::cout << "i = " << i << std::endl;
    //std::cout << "colptr" << colptr[i] << std::endl;
    for (int j = colptr[i]; j < colptr[i+1]; j++) {
      auto& p = partitions[indptr[j] / blockSize];
      int idxInPartition = indptr[j] - (indptr[j] / blockSize ) * blockSize;
      std::get<1>(p).push_back(idxInPartition);
      std::get<2>(p).push_back(values[j]);
      std::get<0>(p).back()++;
    }
  }

  if (n > Spmv_maxRows)
    throw std::invalid_argument(
        "Matrix has too many rows - maximum supported: "
        + std::to_string(Spmv_maxRows));

  std::vector<double> v(cols, 0);
  std::vector<int> m_colptr;
  std::vector<indptr_value> m_indptr_value;

  // now we coalesce partitions
  int cycles = 0;
  for (auto p : partitions) {
    auto p_colptr = std::get<0>(p);
    auto p_indptr = std::get<1>(p);
    auto p_values = std::get<2>(p);
    cycles += this->cycleCount(&p_colptr[0], n, inputWidth);
    spark::spmv::align(p_indptr, sizeof(int) * inputWidth);
    spark::spmv::align(p_values, sizeof(double) * inputWidth);

    std::copy(p_colptr.begin(), p_colptr.end(), back_inserter(m_colptr));
    for (size_t i = 0; i < p_values.size(); i++)
      m_indptr_value.push_back(indptr_value( p_values[i], p_indptr[i]));
  }

  spark::spmv::align(m_colptr, 384);
  std::cout << "Before aliginig " << m_indptr_value.size() << std::endl;
  spark::spmv::align(m_indptr_value, 384); // XXX ? will this actually work
  std::cout << "After aliginig " << m_indptr_value.size() << std::endl;
  std::cout << "Sizeof (struct colptr" << sizeof(indptr_value) << std::endl;
  std::vector<double> out(n, 0);
  align(out, 384);
  spark::spmv::align(v, sizeof(double) * blockSize);
  std::cout << "Cycles ==== " << cycles << std::endl;
  std::cout << "v.size() ==== " << v.size() << std::endl;

  this->gflopsCount = 2.0 * (double)m.nonZeros() / 1E9;
  this->totalCycles = cycles + v.size();

  BlockingResult br;
  br.nPartitions = nPartitions;
  br.n = n;
  br.paddingCycles = out.size() - n; // number of cycles required to align to the burst size
  br.totalCycles = totalCycles;
  br.vector_load_cycles = v.size() / nPartitions; // per partition
  br.m_indptr_values = m_indptr_value;
  br.m_colptr = m_colptr;
  br.outSize = out.size() * sizeof(double);

  return br;
}

template<typename T>
long size_bytes(const std::vector<T>& v) {
  return sizeof(T) * v.size();
}

struct PartitionWriteResult {
  int outStartAddr, outSize, colptrStartAddress, colptrSize;
  int vStartAddress, indptrValuesStartAddress, indptrValuesSize;
};

int align(int bytes, int to) {
  int quot = bytes / to;
  if (bytes % to != 0)
    return (quot + 1) * to;
  return bytes;
}


// write the data for a partition, starting at the given offset
PartitionWriteResult writeDataForPartition(
    int offset,
    const BlockingResult& br,
    const std::vector<double>& v) {
  // for each partition write this down
  PartitionWriteResult pwr;
  pwr.indptrValuesStartAddress = align(offset, 384);
  pwr.indptrValuesSize = size_bytes(br.m_indptr_values);
  std::cout << "indptr values sizses" << pwr.indptrValuesSize << std::endl;
  Spmv_dramWrite(
      pwr.indptrValuesSize,
      pwr.indptrValuesStartAddress,
      (uint8_t *)&br.m_indptr_values[0]);

  //std::cout << "Wrote indptr" << std::endl;
  pwr.vStartAddress = pwr.indptrValuesStartAddress + pwr.indptrValuesSize;
  Spmv_dramWrite(
      size_bytes(v),
      pwr.vStartAddress,
      (uint8_t *)&v[0]);

  //std::cout << "Wrote v" << std::endl;
  pwr.colptrStartAddress = pwr.vStartAddress + size_bytes(v);
  pwr.colptrSize = size_bytes(br.m_colptr);
  Spmv_dramWrite(
      pwr.colptrSize,
      pwr.colptrStartAddress,
      (uint8_t *)&br.m_colptr[0]);

  pwr.outStartAddr = pwr.colptrStartAddress + pwr.colptrSize;
  pwr.outSize = br.outSize;
  return pwr;
}


Eigen::VectorXd ssarch::dfespmv(Eigen::VectorXd x)
{
  using namespace std;

  int cacheSize = this->cacheSize;

  vector<double> v = spark::converters::eigenVectorToStdVector(x);
  spark::spmv::align(v, sizeof(double) * cacheSize);
  spark::spmv::align(v, 384);

  std::vector<int> nrows, paddingCycles, totalCycles, colptrSizes, indptrValuesSizes, outputResultSizes;
  std::vector<long> outputStartAddresses, colptrStartAddresses;
  std::vector<long> vStartAddresses, indptrValuesStartAddresses;

  int offset = 0;
  int i = 0;
  for (auto& p : this->partitions) {
    std::cout << "Doing partition " << i++ << std::endl;
    std::cout << p.to_string() << std::endl;

    nrows.push_back(p.n);
    paddingCycles.push_back(p.paddingCycles);
    totalCycles.push_back(p.totalCycles);

    PartitionWriteResult pr = writeDataForPartition(offset, p, v);
    outputStartAddresses.push_back(pr.outStartAddr);
    outputResultSizes.push_back(pr.outSize);
    colptrStartAddresses.push_back(pr.colptrStartAddress);
    colptrSizes.push_back(pr.colptrSize);
    vStartAddresses.push_back(pr.vStartAddress);
    indptrValuesSizes.push_back(pr.indptrValuesSize);
    indptrValuesStartAddresses.push_back(pr.indptrValuesStartAddress);

    offset = pr.outStartAddr + p.outSize;
  }

  // npartitions and vector load cycles should be the same for all partitions
  int nPartitions = this->partitions[0].nPartitions;
  int vector_load_cycles = this->partitions[0].vector_load_cycles;
  std::cout << "Running on DFE" << std::endl;
  dfesnippets::vectorutils::print_vector(paddingCycles);
  dfesnippets::vectorutils::print_vector(nrows);
  dfesnippets::vectorutils::print_vector(totalCycles);


  Spmv(
      nPartitions,
      vector_load_cycles,
      v.size(),
      &colptrStartAddresses[0],
      &colptrSizes[0],
      &indptrValuesStartAddresses[0],
      &indptrValuesSizes[0],
      &nrows[0],
      &outputResultSizes[0],
      &outputStartAddresses[0],
      &paddingCycles[0],
      &totalCycles[0],
      &vStartAddresses[0]
      );
  std::cout << "Done on DFE" << std::endl;

  std::vector<double> total;
  for (size_t i = 0; i < outputStartAddresses.size(); i++) {
    std::vector<double> tmp(outputResultSizes[i] / sizeof(double), 0);
    Spmv_dramRead(
        size_bytes(tmp),
        outputStartAddresses[i],
        (uint8_t*)&tmp[0]);
    dfesnippets::vectorutils::print_vector(tmp);
    std::copy(tmp.begin(), tmp.begin() + nrows[i], std::back_inserter(total));
  }

  // remove the elements which were only for padding

  return spark::converters::stdvectorToEigen(total);
}

int spark::spmv::getPartitionSize() {
  return Spmv_cacheSize;
}

int spark::spmv::getInputWidth() {
  return Spmv_inputWidth;
}

int spark::spmv::getNumPipes() {
  return Spmv_numPipes;
}

std::vector<EigenSparseMatrix> ssarch::do_partition(EigenSparseMatrix mat, int numPipes) {
  std::vector<EigenSparseMatrix> res;
  int rowsPerPartition = mat.rows() / numPipes;
  std::cout << "Rows per partition" << rowsPerPartition << std::endl;
  int start = 0;
  for (int i = 0; i < numPipes - 1; i++) {
    std::cout << "start finish"  << start << " " << start + rowsPerPartition << std::endl;
    res.push_back(mat.middleRows(start, rowsPerPartition));
    start += rowsPerPartition;
  }
  // put all rows left in the last partition
  res.push_back(mat.middleRows(start, mat.rows() - start));
  return res;
}

void ssarch::preprocess(
    const EigenSparseMatrix mat) {
  this->mat = mat;

  // XXX should be based on the number of pipes constant
  std::vector<EigenSparseMatrix> partitions = do_partition(mat, this->numPipes);

  for (const auto& m : partitions) {
    this->partitions.push_back(do_blocking(m, this->cacheSize, this->inputWidth));
  }
}

