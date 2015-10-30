"""Implements the main DSE loop in spark."""
import json
import argparse
import subprocess
import os
import sys
import re

from subprocess import call

class PrjConfig:
  def __init__(self, p, t, n):
    self.params = p
    self.target = t
    self.name = n
    self.buildRoot='../src/spmv/build/'

  def buildName(self):
    bn = self.name + '_' + self.target
    for k, v in self.params.iteritems():
      bn += '_' + k.replace('_', '') + v
    return bn

  def maxfileName(self):
    return self.name + '.max'

  def __str__(self):
    return 'params = {0}, target = {1}, name = {2}'.format(
        self.params, self.target, self.name)

  def __repr__(self):
    return self.__str__()

  def maxBuildParams(self):
    params = ''
    for k, v in self.params.iteritems():
      params += k + '=' + v + ' '
    return params

  def buildTarget(self):
    if self.target == 'sim':
      return 'DFE_SIM'
    return 'DFE'

  def buildDir(self):
    return os.path.join(self.buildRoot, self.buildName())

  def maxFileLocation(self):
    return os.path.join(self.resultsDir(), self.maxfileName())

  def maxFileTarget(self):
    return os.path.join(self.buildName(), 'results', self.maxfileName())

  def resultsDir(self):
    return os.path.join(self.buildDir(), 'results')

  def libName(self):
    return 'lib{0}_{1}.so'.format(
        self.name, self.target)



def runDse(benchFile, paramsFile):
  dseLog = subprocess.check_output(
      ["../build/main", benchFile, paramsFile])
  dseFile = "dse_out.json"
  params = []
  with open(dseFile) as f:
    data = json.load(f)
    for arch in data['best_architectures']:
      params.append(arch['architecture_params'])
  return params


def runMaxCompilerBuild(prj):
  buildParams = "target={0} buildName={1} maxFileName={2} ".format(
      prj.buildTarget(), prj.buildName(), prj.name)

  print '  Running build'

  cmd = [
      'make',
      'MAX_BUILDPARAMS="' + prj.maxBuildParams() + buildParams + '"',
      "-C",
      prj.buildRoot,
      prj.maxFileTarget()]

  p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
  print '     ---- Building MaxFile ----'
  while p.poll() is None:
      l = p.stdout.readline()
      sys.stdout.write('      ')
      sys.stdout.write(l)
  for l in p.stdout.read().split('\n'):
    if l:
      print '     ', l.rstrip()
  print ''

  forceTimingScore(prj.maxFileLocation())


def forceTimingScore(maxfile):
  # Might want to find a way to do this in python...
  oldts=subprocess.check_output(['grep', 'TIMING_SCORE', maxfile])
  cmd= [
    'sed', '-i', '-e',
    r's/PARAM(TIMING_SCORE,.*)/PARAM(TIMING_SCORE, 0)/',
    maxfile]
  call(cmd)
  newts = subprocess.check_output(['grep', 'TIMING_SCORE', maxfile])
  print '      Changing timing score: {0} --> {1}'.format(oldts.strip(), newts.strip())


def runLibraryBuild(prj):
  print '     ---- Building Library ----'
  out = subprocess.check_output([
    'sliccompile',
    prj.maxFileLocation(),
    'maxfile.o'])
  # print out

  mcdir = os.getenv('MAXCOMPILERDIR')
  maxosdir = os.getenv('MAXELEROSDIR')
  interfaceFile = '../src/spmv/src/SpmvDeviceInterface.cpp'
  cmd =[
	  'g++',
    '-c',
    '-Wall',
    '-std=c++11',
    '-I../include',
    '-I' + prj.resultsDir(),
    '-I' + mcdir + '/include',
    '-I' + mcdir + '/include/slic',
    '-I' + maxosdir + '/include',
    interfaceFile,
    '-o',
    'SpmvDeviceInterface.o'
    ]
  out = subprocess.check_output(cmd)
  LFLAGS="-L${MAXCOMPILERDIR}/lib -L${MAXELEROSDIR}/lib -lmaxeleros -lslic -lm -lpthread"

  cmd =[
      'g++',
      '-fPIC',
      '--std=c++11',
      '-shared',
      '-Wl,-soname,{0}.0 -o {0}'.format(prj.libName()),
      "maxfile.o",
      'SpmvDeviceInterface.o',
      LFLAGS]
  out = subprocess.check_output(cmd)

  # copy the generated library
  call(['cp', prj.libName(), '../lib-generated'])
  call(['cp', prj.libName(), '../lib-generated/{}.0'.format(prj.libName())])


def buildClient(prj):
  print '     ---- Building Client ----'
  out = subprocess.check_output(['make',
    '-C',
    '../build/',
    'test_spmv_' + prj.target])


def runBuilds(prjs):
  for p in  prjs[:1]:
    runMaxCompilerBuild(p)
    runLibraryBuild(p)
    buildClient(p)


def main():

  parser = argparse.ArgumentParser(description='Run Spark DSE flow')
  parser.add_argument('-d', '--dse', action='store_true', default=False)
  parser.add_argument('-t', '--target', choices=['dfe', 'sim'], required=True)
  parser.add_argument('-p', '--param-file', required=True)
  parser.add_argument('-b', '--benchmark-dir', required=True)
  args = parser.parse_args()

  PRJ = 'Spmv'
  buildName = PRJ + '_' + args.target
  prjs = []

  ## Run DSE pass
  if args.dse:
    print 'Running DSE flow'
    # the DSE tool produces a JSON file with architectures to be built
    params = runDse(args.benchmark_dir, args.param_file)
    # builds = [buildName + b for b in builds]
    prjs = [PrjConfig(p, args.target, PRJ) for p  in params]
  else:
    # TODO some default build
    params = []

  print 'Running builds'
  # print prjs

  runBuilds(prjs)


if __name__ == '__main__':
  main()