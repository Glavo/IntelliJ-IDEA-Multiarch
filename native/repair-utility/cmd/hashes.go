package cmd

import (
	"github.com/spf13/cobra"
	"repair/helpers"
	"repair/logger"
	"strings"
)

func init() {
	rootCmd.AddCommand(hashesCmd)
	hashesCmd.PersistentFlags().BoolVarP(&helpers.GenerateHashesFlag, "generate", "g", false, "Generate manifest file for IDE installation")
	hashesCmd.PersistentFlags().BoolVarP(&helpers.CheckHashesFlag, "check", "c", false, "Generate manifest file for IDE installation and check it with the reference generated by JetBrains")
	hashesCmd.PersistentFlags().StringVarP(&helpers.HashesOutputFile, "output-file", "o", "manifest.json", "output file for the manifest")
	hashesCmd.PersistentFlags().StringVarP(&Algorithms, "algorithms", "a", "sha256,crc32", "Comma separated list of algorithms to generate hashes with. CRC32 and sha256 are supported now")
	hashesCmd.PersistentFlags().BoolVarP(&helpers.CompareHashesFlag, "compare", "", false, "Compare two manifest files. Example: repair --compare --file1='/path/to/file1' --file2='/path/to/file2'")
	hashesCmd.PersistentFlags().StringVarP(&helpers.CompareHashesFile1, "file1", "", "", "First manifest file to compare with")
	hashesCmd.PersistentFlags().StringVarP(&helpers.CompareHashesFile2, "file2", "", "", "Second manifest file to compare with")
	_ = hashesCmd.PersistentFlags().MarkHidden("file1")
	_ = hashesCmd.PersistentFlags().MarkHidden("file2")
}

var Algorithms string
var hashesCmd = &cobra.Command{
	Use:   "hashes",
	Short: "generates(-g) or checks(-c) IDE installation integrity",
	Long:  `Hashes aspect generates manifest file of an IDE installation and checks it against standard manifest file generated by JetBrains`,
	Run: func(cmd *cobra.Command, args []string) {
		RunHashesAspect(args)
	},
	PreRun: func(cmd *cobra.Command, args []string) {
		parseFlags()
		logger.InfoLogger.Println("Hashes aspect started")
	},
	PostRun: func(cmd *cobra.Command, args []string) {
		logger.InfoLogger.Println("Hashes aspect finished")
	},
}

func parseFlags() {
	Algorithms = strings.ToUpper(Algorithms)
	helpers.Algorithms = strings.Split(Algorithms, ",")
}

func RunHashesAspect(args []string) {
	if helpers.GenerateHashesFlag {
		helpers.GenerateMainfestFile()
	} else if helpers.CompareHashesFlag {
		helpers.CompareHashes()
	} else {
		helpers.CheckHashes()
	}
}
