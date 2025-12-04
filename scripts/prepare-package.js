#!/usr/bin/env node

/**
 * Prepare script that copies necessary files from package/ to root
 * This allows the package to be installed directly from GitHub
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const PACKAGE_DIR = path.join(__dirname, '..', 'package');
const ROOT_DIR = path.join(__dirname, '..');

// Files and directories to copy from package/ to root
const FILES_TO_COPY = [
  'src',
  'ios',
  'android',
  'react-native.config.js',
  'VisionCamera.podspec',
  'app.plugin.js',
  'tsconfig.json',
  'babel.config.js',
];

function copyRecursive(src, dest) {
  const stat = fs.statSync(src);
  
  if (stat.isDirectory()) {
    // Create destination directory if it doesn't exist
    if (!fs.existsSync(dest)) {
      fs.mkdirSync(dest, { recursive: true });
    }
    
    // Copy all files in the directory
    const entries = fs.readdirSync(src);
    for (const entry of entries) {
      const srcPath = path.join(src, entry);
      const destPath = path.join(dest, entry);
      copyRecursive(srcPath, destPath);
    }
  } else {
    // Copy file
    fs.copyFileSync(src, dest);
  }
}

function preparePackage() {
  console.log('Preparing package for installation...');
  
  // Check if package directory exists
  if (!fs.existsSync(PACKAGE_DIR)) {
    console.error(`Error: package directory not found at ${PACKAGE_DIR}`);
    process.exit(1);
  }
  
  // Copy each file/directory
  for (const item of FILES_TO_COPY) {
    const srcPath = path.join(PACKAGE_DIR, item);
    const destPath = path.join(ROOT_DIR, item);
    
    if (!fs.existsSync(srcPath)) {
      console.warn(`Warning: ${item} not found in package directory, skipping...`);
      continue;
    }
    
    try {
      copyRecursive(srcPath, destPath);
      console.log(`✓ Copied ${item}`);
    } catch (error) {
      console.error(`Error copying ${item}:`, error.message);
      process.exit(1);
    }
  }
  
  // Build the package to generate lib/ directory
  // Note: prepare runs after dependencies are installed, so bob should be available
  console.log('Building package...');
  try {
    // Try yarn first, then fall back to npm
    try {
      execSync('yarn build', {
        cwd: ROOT_DIR,
        stdio: 'inherit',
        env: { ...process.env, NODE_ENV: 'production' }
      });
      console.log('✓ Build complete');
    } catch (yarnError) {
      // Fall back to npm if yarn fails
      execSync('npm run build', {
        cwd: ROOT_DIR,
        stdio: 'inherit',
        env: { ...process.env, NODE_ENV: 'production' }
      });
      console.log('✓ Build complete');
    }
  } catch (error) {
    console.error('Error building package:', error.message);
    console.warn('Warning: Build failed, but package may still work with source files');
    console.warn('You may need to run "yarn build" or "npm run build" manually');
    // Don't exit on build failure - the package might still work with source files
    // via the "react-native" field in package.json
  }
  
  console.log('Package preparation complete!');
}

// Run the prepare script
preparePackage();

